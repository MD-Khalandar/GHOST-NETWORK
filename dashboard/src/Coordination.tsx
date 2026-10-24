import { useEffect, useRef, useState } from "react";
import { ApiError, get, post } from "./api";
import { time } from "./Requests";
import { supplyNames } from "./Resources";

export interface AiProposal {
  proposal_id: string; request_id: string; request_description: string;
  resource_kind: "helper" | "offer"; resource_id: string; resource_label: string | null;
  resource_type: number | null; unit: string | null; coarse_area: string | null;
  quantity: number | null; explanation: string; freshness: "current" | "stale" | "not_found"; created_at_ms: number;
  request_area?: string | null; request_expires_at_ms?: number; resource_expires_at_ms?: number | null;
}
export interface AiRun { run_id: string; request_id: string; status: string; error_code: string | null; created_at_ms: number;
  finished_at_ms?: number | null; candidate_count?: number | null; proposal_count?: number | null }
interface AiStatus { configured: boolean; model: string | null; queued_total: number;
  queued: { request_id: string; attempts: number; next_attempt_at_ms: number }[]; runs: AiRun[];
  budget?: { used: number; limit: number; resets_at_ms: number } }
interface ProposalPage { items: AiProposal[]; next_before: string | null; evaluated_at_ms: number }
export interface ReviewMode { manual_review:boolean; updated_at_ms:number }
const errors: Record<string, string> = {
  MODEL_UNAVAILABLE: "AI is not configured", MODEL_ERROR: "Model request failed", INVALID_MODEL_OUTPUT: "Model suggestion rejected",
  REQUEST_UNAVAILABLE: "Request closed, expired or unavailable", STALE_SOURCE: "Availability changed during the run", STORAGE_UNAVAILABLE: "Storage interrupted",
  MODEL_BUDGET_EXHAUSTED: "AI call budget reached; request remains queued",
};

export function runLabel(run: AiRun, manualReview=true): string {
  if (run.status === "running") return "Checking availability…";
  if (run.status !== "completed") return errors[run.error_code ?? ""] ?? "Check failed";
  if (run.candidate_count === 0) return "No eligible helpers or supplies";
  if (run.proposal_count === 0) return "No suitable matches suggested";
  return run.proposal_count == null ? "Check completed" : `${run.proposal_count} ${run.proposal_count === 1 ? "suggestion" : "suggestions"} ${manualReview ? "for review" : "generated"}`;
}

export function ProposalCard({ proposal, now = Date.now(), operator=false, busy=false, onReview,manualReview=true }: { proposal: AiProposal; now?: number; operator?:boolean; busy?:boolean; manualReview?:boolean; onReview?:(id:string,approve:boolean)=>void }) {
  const current = proposal.freshness === "current" && (proposal.request_expires_at_ms === undefined || proposal.request_expires_at_ms > now) &&
    (proposal.resource_expires_at_ms == null || proposal.resource_expires_at_ms > now);
  return <article className="resource-card">
    <span className={`badge ${current ? "received" : "expired"}`}>
      {current ? manualReview ? "Suggestion · pending human review" : "Suggestion · automatic checks pending" : "Outdated · check the SOS before rerunning"}</span>
    <h2>{proposal.resource_kind === "helper" ? proposal.resource_label || "Helper" : supplyNames[proposal.resource_type ?? 0]}</h2>
    <p className="report-message">{proposal.request_description}</p>
    <p className="report-message">{proposal.explanation}</p>
    <dl className="details">
      <div><dt>Suggested amount</dt><dd>{proposal.resource_kind === "helper" ? "Volunteer help" : proposal.quantity === null ? "Stock unknown" : `${proposal.quantity} ${proposal.unit || "units (unspecified)"}`}</dd></div>
      <div><dt>Reported area</dt><dd>{proposal.coarse_area || "Not provided"}</dd></div>
      <div><dt>SOS area</dt><dd>{proposal.request_area || "Not provided"}</dd></div>
      <div><dt>Suggested</dt><dd>{time(proposal.created_at_ms)}</dd></div>
      {proposal.resource_expires_at_ms != null && <div><dt>Resource availability ends</dt><dd>{time(proposal.resource_expires_at_ms)}</dd></div>}
      <div><dt>SOS</dt><dd className="identifier">{proposal.request_id}</dd></div>
      <div><dt>Resource</dt><dd className="identifier">{proposal.resource_id}</dd></div>
    </dl>
    <p className="subtle">{manualReview ? "Operator approval sends an invitation." : "Eligible suggestions send invitations automatically; existing arrangements, unknown stock or changed availability can prevent a new invitation."} Stock is committed only after owner acceptance and a server check.</p>
    {operator && onReview && <div className="inbox-toolbar">
      {manualReview && <button className="primary-button" disabled={busy || !current || proposal.resource_kind==="offer" && proposal.quantity===null}
        onClick={()=>onReview(proposal.proposal_id,true)}>Approve invitation</button>}
      <button className="secondary-button" disabled={busy} onClick={()=>onReview(proposal.proposal_id,false)}>Reject suggestion</button>
      {proposal.resource_kind==="offer" && proposal.quantity===null && <p>Owner must publish a known quantity before approval.</p>}
    </div>}
  </article>;
}

export function FindHelp({ requestId, csrfToken, operator, onAccessLost }: {
  requestId: string; csrfToken: string; operator: boolean; onAccessLost: (message: string) => void;
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
      await post(`dashboard/sos/${requestId}/coordinate`, csrfToken, controller.signal);
      if (pending.current === controller) setMessage("Queued. Open AI suggestions to follow progress.");
    } catch (failure) {
      if (pending.current !== controller) return;
      const text = failure instanceof ApiError ? failure.message : "Couldn't queue the AI run. Try again.";
      if (failure instanceof ApiError && [401, 403].includes(failure.status)) onAccessLost(text);
      else { setError(true); setMessage(text); }
    } finally { if (pending.current === controller) { pending.current = null; setBusy(false); } }
  }
  return <div className="help-note"><strong>Find possible help</strong>
    <p>The cloud AI checks relevant help types and current availability when this request's candidates change. Reported areas guide ranking; travel and access still need verification.</p>
    {operator ? <button className="secondary-button" disabled={busy} onClick={() => void run()}>{busy ? "Queueing…" : "Find help with AI"}</button> :
      <p>An operator can request another AI run.</p>}
    {message && <p role={error ? "alert" : "status"}>{message}</p>}
  </div>;
}

export default function Coordination({ csrfToken, onAccessLost, operator=false }: { csrfToken: string; onAccessLost: (message: string) => void; operator?:boolean }) {
  const [mode,setMode]=useState<ReviewMode|null>(null);
  const [changingMode,setChangingMode]=useState(false);
  const [status, setStatus] = useState<AiStatus | null>(null);
  const [cases,setCases]=useState<{proposal_id:string;request_id:string;resource_id:string;state:number;quantity:number|null;unit:string;deadline_ms:number}[]>([]);
  const [reviewing,setReviewing]=useState(false);
  const [items, setItems] = useState<AiProposal[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [nextBefore, setNextBefore] = useState<string | null>(null);
  const [previous, setPrevious] = useState<(string | null)[]>([]);
  const [filter, setFilter] = useState("");
  const [checkedAt, setCheckedAt] = useState<number | null>(null);
  const selection = useRef<{ before: string | null; requestId: string }>({ before: null, requestId: "" });
  const pending = useRef<AbortController | null>(null);
  const accessLost = useRef(onAccessLost); accessLost.current = onAccessLost;
  async function load(before = selection.current.before, requestId = selection.current.requestId): Promise<boolean> {
    if (pending.current) return false;
    const controller = new AbortController(); pending.current = controller; setBusy(true);
    try {
      const [state, proposals, assistance,settings] = await Promise.all([get<AiStatus>("dashboard/runs", controller.signal),
        get<ProposalPage>(`dashboard/proposals?limit=30${before ? `&before=${encodeURIComponent(before)}` : ""}${requestId ? `&request_id=${encodeURIComponent(requestId)}` : ""}`, controller.signal),get<{items:typeof cases}>("dashboard/assistance",controller.signal),get<ReviewMode>("dashboard/coordination-settings",controller.signal)]);
      if (pending.current !== controller) return false;
      setMode(settings);setCases(assistance.items);setStatus(state); setItems(proposals.items); setError(null);
      setNextBefore(proposals.next_before); setCheckedAt(proposals.evaluated_at_ms);
      selection.current = { before, requestId };
      return true;
    } catch (failure) {
      if (pending.current !== controller) return false;
      const text = failure instanceof ApiError ? failure.message : "Couldn't load AI suggestions.";
      if (failure instanceof ApiError && [401, 403].includes(failure.status)) accessLost.current(text);
      else setError(text);
      return false;
    } finally { if (pending.current === controller) { pending.current = null; setBusy(false); } }
  }
  async function changeMode(manual:boolean) {
    if(pending.current || !operator)return;
    const controller=new AbortController();pending.current=controller;setBusy(true);setChangingMode(true);
    try {
      const settings=await post<ReviewMode>("dashboard/coordination-settings",csrfToken,controller.signal,{manual_review:manual});
      if(pending.current===controller){setMode(settings);setError(null);}
    }catch(failure){
      if(pending.current!==controller)return;
      if(failure instanceof ApiError && [401,403].includes(failure.status))accessLost.current(failure.message);
      else setError(failure instanceof ApiError?failure.message:"Could not change review mode. Refresh and retry.");
    }finally{if(pending.current===controller){pending.current=null;setBusy(false);setChangingMode(false);}}
  }
  async function review(id:string,approve:boolean) {
    if(reviewing)return;
    if(!window.confirm(approve?"Send this invitation to the owner? Nothing is reserved until acceptance.":"Reject this suggestion?"))return;
    setReviewing(true);const controller=new AbortController();
    try{await post(`dashboard/proposals/${id}/${approve?"approve":"reject"}`,csrfToken,controller.signal);await load();}
    catch(failure){if(failure instanceof ApiError && [401,403].includes(failure.status))accessLost.current(failure.message);
      else setError(failure instanceof ApiError?failure.message:"Could not save review. Refresh and retry.");}
    finally{setReviewing(false);}
  }
  useEffect(() => {
    void load(); const timer = window.setInterval(() => { if (!document.hidden) void load(); }, 5000);
    return () => { window.clearInterval(timer); pending.current?.abort(); pending.current = null; };
  }, [csrfToken]);
  return <section aria-label="AI coordination" aria-busy={busy}>
    <div className="inbox-toolbar"><strong>{status ? status.configured ? "Cloud AI configured" : "Cloud AI not configured" : "Loading AI status…"}</strong>
      <button className="secondary-button" disabled={busy} onClick={() => void load()}>Refresh AI status</button></div>
    {mode && <ReviewModeControl mode={mode} operator={operator} busy={busy || reviewing} changing={changingMode} onChange={manual=>void changeMode(manual)}/>}
    <p className="subtle">This page refreshes every five seconds. Owners must accept invitations before stock is reserved. Invitations and owner decisions appear below; a commitment does not confirm delivered assistance.</p>
    <form className="inbox-toolbar ai-filter" onSubmit={event => { event.preventDefault(); void load(null, filter.trim()).then(ok => { if (ok) setPrevious([]); }); }}>
      <label htmlFor="ai-request-filter">Filter by SOS ID</label>
      <input id="ai-request-filter" value={filter} onChange={event => setFilter(event.target.value.trim())} placeholder="All requests"
        pattern="[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}" disabled={busy}/>
      <button className="secondary-button" disabled={busy}>Apply filter</button>
      {selection.current.requestId && <button type="button" className="secondary-button" disabled={busy} onClick={() => {
        void load(null, "").then(ok => { if (ok) { setFilter(""); setPrevious([]); } });
      }}>Show all requests</button>}
    </form>
    {checkedAt !== null && <p className="subtle">Availability last checked {time(checkedAt)} · {items.length} suggestions on this page</p>}
    {status && !status.configured && <p className="error-banner">The server needs an AI provider, its API key and a model. Saved SOS reports and mesh relay continue to work.</p>}
    {status?.budget && status.budget.used >= status.budget.limit && <p className="help-note" role="status">
      AI checks are paused until {time(status.budget.resets_at_ms)}. Requests remain queued; uploads and nearby relay continue.
    </p>}
    {error && <p className="error error-banner" role="alert">{error} Previously loaded results may be outdated.</p>}
    <div className="resource-grid">{items.map(item => <ProposalCard key={item.proposal_id} proposal={item} operator={operator} busy={reviewing || busy} manualReview={mode?.manual_review!==false} onReview={(id,approve)=>void review(id,approve)}/>)}</div>
    <nav className="inbox-toolbar ai-pages" aria-label="Suggestion pages">
      {previous.length > 0 && <>
        <button className="secondary-button" disabled={busy} onClick={() => { void load(null).then(ok => { if (ok) setPrevious([]); }); }}>Newest suggestions</button>
        <button className="secondary-button" disabled={busy} onClick={() => { void load(previous.at(-1)!).then(ok => { if (ok) setPrevious(p => p.slice(0, -1)); }); }}>Previous page</button>
      </>}
      {nextBefore && <button className="secondary-button" disabled={busy} onClick={() => {
        const current = selection.current.before;
        void load(nextBefore).then(ok => { if (ok) setPrevious(p => [...p, current]); });
      }}>Older suggestions</button>}
    </nav>
    {status && !items.length && <div className="empty-state"><h2>No suggestions on this page</h2><p>{status.queued_total ? "Requests are queued for the AI coordinator." : "Check the recent run results below. Operators can request a fresh check from an open SOS."}</p></div>}
    {cases.length>0 && <div className="help-note"><h2>Assistance coordination</h2><p>Latest 100 arrangements. Phone delivery can wait for a gateway or mesh route.</p>
      {cases.map(item=><div className="resource-card" key={item.proposal_id}><strong>{["","Invitation awaiting owner","Commitment confirmed by server","Declined / rejected","Cancelled","Expired","Requester reported resolved","Help received (confirmed by requester)"][item.state]}</strong>
        <p>SOS {item.request_id} ? Resource {item.resource_id}</p><p>{item.quantity===null?"Volunteer assistance":`${item.quantity} ${item.unit}`} ? deadline {time(item.deadline_ms)}</p>
        <p className="subtle">{item.state===7 ? "Receipt is reported by the requester. Other phones update when this status reaches them." : "This status does not confirm physical delivery or that an offline requester has received the update."}</p></div>)}
    </div>}
    {status && <div className="help-note"><h2>Recent AI runs</h2>
      <p>{status.queued_total} requests waiting or running · latest 20 runs</p>
      {status.queued.filter(job => job.attempts > 0).map(job => <p className="subtle" key={job.request_id}>
        SOS {job.request_id.slice(0, 8)} · {job.attempts} failed {job.attempts === 1 ? "attempt" : "attempts"} · next retry {time(job.next_attempt_at_ms)}
      </p>)}
      {status.runs.map(run => <div className="run-row" key={run.run_id}>
        <span className="identifier">{run.request_id.slice(0, 8)}</span><strong>{runLabel(run,mode?.manual_review!==false)}</strong><span>{time(run.created_at_ms)}</span>
      </div>)}
    </div>}
  </section>;
}

export function ReviewModeControl({mode,operator,busy,changing=false,onChange}: {
  mode:ReviewMode;operator:boolean;busy:boolean;changing?:boolean;onChange:(manual:boolean)=>void;
}) {
  return <div className="help-note" aria-busy={changing}>
    <strong>{mode.manual_review?"Manual review":"Automatic invitations"}</strong>
    {operator && <label className="review-mode-toggle"><input type="checkbox" role="switch" checked={mode.manual_review}
      disabled={busy} onChange={event=>onChange(event.target.checked)}/>Require operator approval</label>}
    <p>{mode.manual_review?"New invitations wait for operator approval.":"Eligible AI suggestions send invitations automatically. Helpers still choose whether to accept."}</p>
    {operator && <p className="subtle">Applies to the whole server and survives restarts. Turning manual review off processes eligible pending suggestions too. Existing invitations and commitments continue.</p>}
    {changing && <p role="status">Saving review mode...</p>}
  </div>;
}
