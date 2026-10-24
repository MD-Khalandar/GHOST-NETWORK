import { useEffect, useRef, useState } from "react";
import { ApiError, get } from "./api";
import { time } from "./Requests";

export interface ResourceRecord {
  owner_node_id: string; offer_id?: string; display_name?: string | null; coarse_area: string | null;
  help_types?: number[]; availability?: number; resource_type?: number; quantity?: number | null; unit?: string | null;
  available_until_ms?: number; expires_at_ms: number; received_at_ms: number; created_at_ms: number; cloud_revision: number;
  state?: number;
}
export const helpNames = ["Other", "First aid", "Transport", "Search", "Shelter", "Food", "Water", "Other help"];
export const supplyNames = ["Other", "Water", "Food", "Shelter", "Medical supplies", "Transport", "Other supplies"];

export function ResourceCard({ item, kind, now }: { item: ResourceRecord; kind: "helpers" | "offers"; now: number }) {
  const expired = Math.min(item.expires_at_ms, item.available_until_ms ?? item.expires_at_ms) <= now;
  const withdrawn = kind === "offers" && item.state === 2;
  const available = !withdrawn && !expired && (kind === "offers" || item.availability === 1);
  return <article className="resource-card">
    <span className={`badge ${available ? "received" : "expired"}`}>{withdrawn ? "Withdrawn" : expired ? "Expired" : available ? "Reported available" : "Reported unavailable / unknown"}</span>
    <h2>{kind === "helpers" ? item.display_name || `Helper ${item.owner_node_id.slice(0, 8)}` : supplyNames[item.resource_type ?? 0]}</h2>
    <p>{kind === "helpers" ? item.help_types?.map(type => helpNames[type]).join(" · ") :
      item.quantity === null ? "Quantity not provided" : `${item.quantity} ${item.unit || "units (unspecified)"}`}</p>
    <dl className="details">
      <div><dt>Area</dt><dd>{item.coarse_area || "Not provided"}</dd></div>
      <div><dt>Owner</dt><dd className="identifier">{item.owner_node_id.slice(0, 12)}</dd></div>
      <div><dt>Reported</dt><dd>{time(item.created_at_ms)}</dd></div>
      <div><dt>Availability expires</dt><dd>{time(Math.min(item.expires_at_ms, item.available_until_ms ?? item.expires_at_ms))}</dd></div>
    </dl>
    <p className="subtle">Owner-reported availability. No reservation or assistance is confirmed.</p>
  </article>;
}

export default function Resources({ kind, token, onAccessLost }: {
  kind: "helpers" | "offers"; token: string; onAccessLost: (message: string) => void;
}) {
  const [items, setItems] = useState<ResourceRecord[]>([]);
  const [before, setBefore] = useState<number | null>(null);
  const [busy, setBusy] = useState(true);
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [now, setNow] = useState(Date.now);
  const pending = useRef<AbortController | null>(null);
  const accessLost = useRef(onAccessLost); accessLost.current = onAccessLost;
  async function load(cursor?: number) {
    pending.current?.abort(); const controller = new AbortController(); pending.current = controller;
    setBusy(true); setError(null);
    try {
      const page = await get<{ items: ResourceRecord[]; next_before: number | null }>(`dashboard/${kind}?limit=30${cursor === undefined ? "" : `&before=${cursor}`}`, token, controller.signal);
      if (pending.current !== controller) return;
      setItems(previous => cursor === undefined ? page.items : [...previous, ...page.items.filter(item => !previous.some(old =>
        (old.offer_id ?? old.owner_node_id) === (item.offer_id ?? item.owner_node_id)))]);
      setBefore(page.next_before); setLoaded(true); setNow(Date.now());
    } catch (failure) {
      if (pending.current !== controller) return;
      const message = failure instanceof ApiError ? failure.message : "Couldn't load availability. Check your connection and retry.";
      if (failure instanceof ApiError && [401, 403].includes(failure.status)) accessLost.current(message);
      else setError(message);
    } finally { if (pending.current === controller) { pending.current = null; setBusy(false); } }
  }
  useEffect(() => {
    void load(); const timer = window.setInterval(() => setNow(Date.now()), 30000);
    return () => { window.clearInterval(timer); pending.current?.abort(); pending.current = null; };
  }, [kind, token]);
  return <section aria-label={kind === "helpers" ? "Helper directory" : "Supply directory"} aria-busy={busy}>
    <div className="inbox-toolbar"><strong>{items.length} {kind === "helpers" ? "helpers" : "supply offers"} loaded</strong>
      <button className="secondary-button" disabled={busy} onClick={() => void load()}>{busy ? "Loading…" : "Refresh availability"}</button></div>
    <p className="subtle">Only owner-signed reports uploaded with consent appear here. Availability can change; matching and owner approval come next.</p>
    {error && <p className="error error-banner" role="alert">{error} {loaded && "Showing previously loaded availability."}</p>}
    <div className="resource-grid">{items.map(item => <ResourceCard key={item.offer_id ?? item.owner_node_id} item={item} kind={kind} now={now}/>)}</div>
    {loaded && !items.length && <div className="empty-state"><h2>No {kind === "helpers" ? "helpers" : "supplies"} received yet</h2><p>Use Offer help on a phone, allow online coordination, and connect to a gateway.</p></div>}
    {before !== null && <div className="load-more"><button className="secondary-button" disabled={busy} onClick={() => void load(before)}>Load older availability</button></div>}
  </section>;
}
