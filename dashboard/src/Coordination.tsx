import { useEffect, useRef, useState } from "react";
import { ApiError, get, post } from "./api";
import { time } from "./Requests";
import { supplyNames } from "./Resources";

export interface AiProposal {
  proposal_id: string; request_id: string; request_description: string;
  resource_kind: "helper" | "offer"; resource_id: string; resource_label: string | null;
  resource_type: number | null; unit: string | null; coarse_area: string | null;
  quantity: number | null; explanation: string; freshness: "current" | "stale" | "not_found"; created_at_ms: number;
}
interface AiRun { run_id: string; request_id: string; status: string; error_code: string | null; created_at_ms: number }
interface AiStatus { configured: boolean; model: string | null; queued: { request_id: string; attempts: number }[]; runs: AiRun[] }
const errors: Record<string, string> = {
  MODEL_UNAVAILABLE: "AI is not configured", MODEL_ERROR: "Model request failed", INVALID_MODEL_OUTPUT: "Model suggestion rejected",
  REQUEST_UNAVAILABLE: "Request expired or unavailable", STALE_SOURCE: "Availability changed during the run", STORAGE_UNAVAILABLE: "Storage interrupted",
};

export function ProposalCard({ proposal }: { proposal: AiProposal }) {
  return <article className="resource-card">
    <span className={`badge ${proposal.freshness === "current" ? "received" : "expired"}`}>
      {proposal.freshness === "current" ? "Suggestion · pending human review" : "Outdated · rerun AI"}</span>
    <h2>{proposal.resource_kind === "helper" ? proposal.resource_label || "Helper" : supplyNames[proposal.resource_type ?? 0]}</h2>
    <p className="report-message">{proposal.request_description}</p>
    <p className="report-message">{proposal.explanation}</p>
    <dl className="details">
      <div><dt>Suggested amount</dt><dd>{proposal.resource_kind === "helper" ? "Volunteer help" : proposal.quantity === null ? "Stock unknown" : `${proposal.quantity} ${proposal.unit || "units (unspecified)"}`}</dd></div>
      <div><dt>Reported area</dt><dd>{proposal.coarse_area || "Not provided"}</dd></div>
      <div><dt>SOS</dt><dd className="identifier">{proposal.request_id}</dd></div>
      <div><dt>Resource</dt><dd className="identifier">{proposal.resource_id}</dd></div>
    </dl>
    <p className="subtle">Nothing is reserved or sent to a phone. Operator approval and the owner's acceptance are required next.</p>
  </article>;
}

export function FindHelp({ requestId, token, operator, onAccessLost }: {
  requestId: string; token: string; operator: boolean; onAccessLost: (message: string) => void;
}) {
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState(false);
  const pending = useRef<AbortController | null>(null);
  useEffect(() => () => pending.current?.abort(), []);
  async function run() {
    const controller = new AbortController(); pending.current = controller;
    setBusy(true); setError(false); setMessage(null);
    try {
      await post(`dashboard/sos/${requestId}/coordinate`, token, controller.signal);
      if (pending.current === controller) setMessage("Queued. Open AI suggestions to follow progress.");
    } catch (failure) {
      if (pending.current !== controller) return;
      const text = failure instanceof ApiError ? failure.message : "Couldn't queue the AI run. Try again.";
      if (failure instanceof ApiError && [401, 403].includes(failure.status)) onAccessLost(text);
      else { setError(true); setMessage(text); }
    } finally { if (pending.current === controller) { pending.current = null; setBusy(false); } }
  }
  return <div className="help-note"><strong>Find possible help</strong>
    <p>The cloud AI checks uploaded requests and current availability automatically when configured.</p>
    {operator ? <button className="secondary-button" disabled={busy} onClick={() => void run()}>{busy ? "Queueing…" : "Find help with AI"}</button> :
      <p>An operator can request another AI run.</p>}
    {message && <p role={error ? "alert" : "status"}>{message}</p>}
  </div>;
}

export default function Coordination({ token, onAccessLost }: { token: string; onAccessLost: (message: string) => void }) {
  const [status, setStatus] = useState<AiStatus | null>(null);
  const [items, setItems] = useState<AiProposal[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const pending = useRef<AbortController | null>(null);
  const accessLost = useRef(onAccessLost); accessLost.current = onAccessLost;
  async function load() {
    if (pending.current) return;
    const controller = new AbortController(); pending.current = controller; setBusy(true);
    try {
      const [state, proposals] = await Promise.all([get<AiStatus>("dashboard/runs", token, controller.signal),
        get<{ items: AiProposal[] }>("dashboard/proposals", token, controller.signal)]);
      if (pending.current !== controller) return;
      setStatus(state); setItems(proposals.items); setError(null);
    } catch (failure) {
      if (pending.current !== controller) return;
      const text = failure instanceof ApiError ? failure.message : "Couldn't load AI suggestions.";
      if (failure instanceof ApiError && [401, 403].includes(failure.status)) accessLost.current(text);
      else setError(text);
    } finally { if (pending.current === controller) { pending.current = null; setBusy(false); } }
  }
  useEffect(() => {
    void load(); const timer = window.setInterval(() => { if (!document.hidden) void load(); }, 5000);
    return () => { window.clearInterval(timer); pending.current?.abort(); pending.current = null; };
  }, [token]);
  return <section aria-label="AI coordination" aria-busy={busy}>
    <div className="inbox-toolbar"><strong>{status ? status.configured ? "Cloud AI configured" : "Cloud AI not configured" : "Loading AI status…"}</strong>
      <button className="secondary-button" disabled={busy} onClick={() => void load()}>Refresh AI status</button></div>
    <p className="subtle">Suggestions refresh every five seconds. They do not confirm help. Approval and owner acceptance are the next implementation step.</p>
    {status && !status.configured && <p className="error-banner">The server needs an AI provider, its API key and a model. Saved SOS reports and mesh relay continue to work.</p>}
    {error && <p className="error error-banner" role="alert">{error} Previously loaded results may be outdated.</p>}
    <div className="resource-grid">{items.map(item => <ProposalCard key={item.proposal_id} proposal={item}/>)}</div>
    {status && !items.length && <div className="empty-state"><h2>No suggestions yet</h2><p>{status.queued.length ? "Requests are queued for the AI coordinator." : "New uploads trigger checks when cloud AI is configured. Operators can also run a check from an SOS."}</p></div>}
    {status && <div className="help-note"><h2>Recent AI runs</h2>
      <p>{status.queued.length} requests waiting or running · latest 20 runs / 30 suggestions</p>
      {status.runs.map(run => <div className="run-row" key={run.run_id}>
        <span className="identifier">{run.request_id.slice(0, 8)}</span><strong>{run.status === "completed" ? "Check completed" : run.status === "running" ? "Checking availability…" : errors[run.error_code ?? ""] ?? "Check failed"}</strong><span>{time(run.created_at_ms)}</span>
      </div>)}
    </div>}
  </section>;
}
