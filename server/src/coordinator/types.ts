export interface SourceVersion { event_id: string; cloud_revision: number }

export interface SosSource {
  request_id: string;
  source: SourceVersion;
  category: number;
  description: string;
  people_count: number | null;
  coarse_area: string | null;
  landmark: string | null;
  expires_at_ms: number;
}

export interface ResourceSource {
  kind: "helper" | "offer";
  resource_id: string;
  owner_node_id: string;
  source: SourceVersion;
  coarse_area: string | null;
  help_types: number[];
  resource_type: number | null;
  quantity: number | null;
  unit: string | null;
  expires_at_ms: number;
}

export type Specialist = "helper" | "supply";
export interface FollowUp {
  awaiting_review: number;
  arrangements: { proposal_id:string; resource_id:string; state:number; version:number; quantity:number|null; unit:string }[];
}
export interface MatchContext { request: SosSource; resources: ResourceSource[]; specialist?:Specialist; follow_up?:FollowUp }
export interface MatchDraft {
  request_id: string;
  resource_kind: "helper" | "offer";
  resource_id: string;
  quantity: number | null;
  explanation: string;
}

/** Model output is untrusted, even when a provider enforces the tool schema. */
export interface ProposalModel {
  readonly available: boolean;
  readonly name: string | null;
  suggest(context: MatchContext): Promise<unknown>;
}

export type FailureCode = "MODEL_UNAVAILABLE" | "MODEL_ERROR" | "INVALID_MODEL_OUTPUT" |
  "REQUEST_UNAVAILABLE" | "STALE_SOURCE" | "STORAGE_UNAVAILABLE" | "MODEL_BUDGET_EXHAUSTED";
export class CoordinatorFailure extends Error {
  constructor(readonly code: FailureCode) { super(code); }
}

export interface ReviewProposal extends MatchDraft {
  proposal_id: string;
  run_id: string;
  status: "pending_review";
  request_source: SourceVersion;
  resource_source: SourceVersion;
  owner_node_id: string;
  created_at_ms: number;
}

export interface RunResult {
  run_id: string | null;
  status: "completed" | "unavailable" | "error";
  code: FailureCode | null;
  proposal_ids: string[];
}

export const MAX_PROPOSALS = 10;
export const CANDIDATES_PER_KIND = 50;
export const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
