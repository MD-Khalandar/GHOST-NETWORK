import { CANDIDATES_PER_KIND } from "./types.js";

// Both inference and scheduling use this selection. s is the SOS row; $1 is now.
// Transport and unspecified help remain candidates for human/model assessment.
const helpers = `(CASE s.category WHEN 1 THEN ARRAY[1,2,7] WHEN 2 THEN ARRAY[1,2,3,7]
  WHEN 3 THEN ARRAY[2,6,7] WHEN 4 THEN ARRAY[2,5,7] WHEN 5 THEN ARRAY[2,4,7] ELSE ARRAY[1,2,3,4,5,6,7] END)`;
const supplies = `(CASE s.category WHEN 1 THEN ARRAY[4,5,6] WHEN 2 THEN ARRAY[3,4,5,6]
  WHEN 3 THEN ARRAY[1,5,6] WHEN 4 THEN ARRAY[2,5,6] WHEN 5 THEN ARRAY[3,5,6] ELSE ARRAY[1,2,3,4,5,6] END)`;
const area = (field: string) => `NULLIF(lower(regexp_replace(btrim(${field}), '[[:space:]]+', ' ', 'g')), '')`;

const selectCandidates = (source: string, followThrough=false) => `SELECT * FROM (
  SELECT r.*, ROW_NUMBER() OVER (PARTITION BY CASE WHEN r.kind = 3 THEN 3 ELSE 5 END
    ORDER BY CASE WHEN ${area("r.data->>'coarse_area'")} = ${area("s.coarse_area")} THEN 0
      WHEN ${area("r.data->>'coarse_area'")} IS NULL THEN 1 ELSE 2 END,
      r.created_at_ms DESC, r.event_id DESC) AS candidate_rank
  FROM ${source} r
  WHERE r.expires_at_ms > $1 AND r.owner_node_id <> s.sender_node_id
    ${followThrough ? `AND NOT EXISTS(SELECT 1 FROM coordinator_proposals p WHERE p.request_event_id=s.event_id AND p.resource_event_id=r.event_id)
    AND NOT EXISTS(SELECT 1 FROM assistance_cases a WHERE a.request_id=s.request_id AND a.resource_id=r.entity_id
      AND (a.state IN(1,2) OR a.resource_event_id=r.event_id))` : ""}
    AND (CASE WHEN r.kind = 3 THEN r.data->>'availability' = '1'
      AND EXISTS (SELECT 1 FROM jsonb_array_elements_text(r.data->'help_types') h(value) WHERE h.value::integer = ANY(${helpers}))
    ELSE COALESCE(r.data->>'state', '1') = '1'
      AND COALESCE((r.data->>'available_until_ms')::bigint, r.expires_at_ms) > $1
      AND (r.data->>'quantity' IS NULL OR (r.data->>'quantity')::bigint > 0)
      AND (r.data->>'resource_type')::integer = ANY(${supplies}) END)
) ranked WHERE candidate_rank <= ${CANDIDATES_PER_KIND}`;

export const candidateSql = selectCandidates("coordinator_resource_available");
export const teamCandidateSql = selectCandidates("coordinator_resource_available",true);

// Signed source versions identify the actual bounded input; unrelated uploads,
// old replays and stock outside this candidate set do not spend another model call.
export const inputHashSql = `md5(s.event_id::text || ':' || s.cloud_revision::text || ':' || COALESCE((
  SELECT string_agg(c.event_id::text || ':' || c.cloud_revision::text || ':' || COALESCE(c.data->>'quantity','') || ':' || COALESCE(c.data->>'availability',''), ',' ORDER BY c.event_id)
  FROM (${selectCandidates("current_resources",true)}) c), '') || ':' || COALESCE((
  SELECT string_agg(a.proposal_id::text || ':' || a.version::text, ',' ORDER BY a.proposal_id)
  FROM assistance_cases a WHERE a.request_id=s.request_id), '') || ':' || COALESCE((
  SELECT string_agg(p.proposal_id::text || ':' || COALESCE(r.event_id::text,'') || ':' ||
    COALESCE(r.data->>'quantity','') || ':' || COALESCE(r.data->>'availability','') || ':' || COALESCE(r.data->>'state','') || ':' ||
    CASE WHEN r.expires_at_ms>$1 AND COALESCE((r.data->>'available_until_ms')::bigint,r.expires_at_ms)>$1 THEN 'live' ELSE 'expired' END,
    ',' ORDER BY p.proposal_id) FROM coordinator_proposals p LEFT JOIN current_resources r ON r.entity_id=p.resource_id
  WHERE p.request_event_id=s.event_id AND NOT EXISTS(SELECT 1 FROM assistance_cases a WHERE a.proposal_id=p.proposal_id)), ''))`;
