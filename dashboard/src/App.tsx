import { useEffect, useRef, useState, type FormEvent } from "react";
import { ApiError, get, type Session, type SosPage, type SosReport } from "./api";
import { RequestDetails, RequestList, time } from "./Requests";
import Resources from "./Resources";
import Coordination, { FindHelp } from "./Coordination";
import { LocationMap } from "./LocationMap";

export default function App() {
  // Credentials live only in this tab's memory and disappear on reload/sign-out.
  const [session, setSession] = useState<(Session & { token: string }) | null>(null);
  const [tab, setTab] = useState<"sos" | "helpers" | "offers" | "ai">("sos");
  const [tokenInput, setTokenInput] = useState("");
  const [reports, setReports] = useState<SosReport[]>([]);
  const [nextBefore, setNextBefore] = useState<number | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [updated, setUpdated] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [now, setNow] = useState(Date.now);
  const pending = useRef<AbortController | null>(null);
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 30_000);
    return () => { window.clearInterval(timer); pending.current?.abort(); };
  }, []);

  function clearSession() {
    pending.current?.abort(); pending.current = null;
    setSession(null); setTokenInput(""); setReports([]); setNextBefore(null); setSelected(null); setUpdated(null); setBusy(false);
    setTab("sos");
  }

  async function load(signingIn = false, before?: number) {
    pending.current?.abort();
    const controller = new AbortController(); pending.current = controller;
    const token = signingIn ? tokenInput.trim() : session!.token;
    setBusy(true); setError(null);
    try {
      const identity = signingIn ? await get<Session>("auth/session", token, controller.signal) : session!;
      if (identity.role !== "viewer" && identity.role !== "operator") throw new ApiError(403);
      const page = await get<SosPage>(`dashboard/sos?limit=30${before === undefined ? "" : `&before=${before}`}`, token, controller.signal);
      if (pending.current !== controller) return;
      setSession({ ...identity, token }); setTokenInput("");
      setReports(previous => before === undefined ? page.items : [...previous, ...page.items.filter(item => !previous.some(old => old.request_id === item.request_id))]);
      setNextBefore(page.next_before);
      if (before === undefined) {
        setSelected(previous => page.items.some(item => item.request_id === previous) ? previous : page.items[0]?.request_id ?? null);
        setUpdated(Date.now());
      }
      setNow(Date.now());
    } catch (failure) {
      if (pending.current !== controller) return;
      if (failure instanceof ApiError && [401, 403].includes(failure.status)) clearSession();
      setError(failure instanceof ApiError ? failure.message : "Couldn't reach the server. Check your connection and try again.");
    } finally {
      if (pending.current === controller) { pending.current = null; setBusy(false); }
    }
  }

  function signIn(event: FormEvent) { event.preventDefault(); void load(true); }
  const report = reports.find(item => item.request_id === selected);

  return <>
    <header className="topbar"><div className="brand"><svg viewBox="0 0 32 32" aria-hidden="true"><path d="M5 23V9l11 7L27 9v14l-11-7z" fill="none" stroke="currentColor" strokeWidth="2"/><circle cx="16" cy="16" r="3" fill="currentColor"/></svg><span>GHOST</span><span className="brand-divider"/><span className="desk-label">SOS desk</span></div>
      {session && <div className="session"><span>{session.id} <span className="role">/ {session.role}</span></span><button className="quiet-button" onClick={() => { clearSession(); setError(null); }}>Sign out</button></div>}
    </header>
    <main>
      <div className="page-heading"><div><div className="eyebrow">{session ? "Server inbox" : "GHOST coordination"}</div><h1>{tab === "ai" ? "AI suggestions" : tab === "helpers" ? "People who can help" : tab === "offers" ? "Available supplies" : "Requests for help"}<span className="heading-dot">.</span></h1><p>See requests and availability that have reached the server.</p></div><span className="read-only">{session?.role === "operator" ? "Operator desk" : "Read-only desk"}</span></div>
      {!session ? <section className="sign-in-panel" aria-labelledby="sign-in-title"><div><div className="section-number">01 / ACCESS</div><h2 id="sign-in-title">Open your desk</h2><p>Use a viewer or operator access token supplied by the server administrator.</p><p className="subtle">Your token stays in this tab. Reloading signs you out.</p></div><form onSubmit={signIn}>
        <label htmlFor="access-token">Access token</label><input id="access-token" type="password" value={tokenInput} onChange={event => setTokenInput(event.target.value)} required minLength={32} maxLength={256} autoComplete="off" spellCheck={false} disabled={busy} aria-describedby={error ? "sign-in-error" : undefined}/>
        {error && <p id="sign-in-error" className="error" role="alert">{error}</p>}
        <button className="primary-button" disabled={busy}>{busy ? "Opening desk…" : "Open SOS desk"}<span aria-hidden="true">↗</span></button>
      </form></section> : <>
        <nav className="desk-tabs" aria-label="Desk sections">{([['sos', 'SOS requests'], ['helpers', 'Helpers'], ['offers', 'Supplies'], ['ai', 'AI suggestions']] as const).map(([value, title]) =>
          <button key={value} className="secondary-button" aria-current={tab === value ? "page" : undefined} onClick={() => setTab(value)}>{title}</button>)}</nav>
        {tab === "ai" ? <Coordination token={session.token} onAccessLost={message => { clearSession(); setError(message); }}/>
          : tab !== "sos" ? <Resources key={tab} kind={tab} token={session.token} onAccessLost={message => { clearSession(); setError(message); }}/> : <>
        <div className="inbox-toolbar"><div><strong>{reports.length} {reports.length === 1 ? "request" : "requests"} loaded</strong><span className="subtle">{updated ? `Last refreshed ${time(updated)}` : "Loading requests"}</span></div><button className="secondary-button" disabled={busy} onClick={() => void load()}>{busy ? "Loading…" : "Refresh requests"}</button></div>
        <div className="inbox-note"><span className="status-dot" aria-hidden="true"/><p>Only uploaded reports appear here. Phones still offline may hold other requests. Refresh to check for new arrivals.</p></div>
        {error && <div className="error error-banner" role="alert">{error} {reports.length > 0 && "Showing previously loaded reports."}</div>}
        {reports.length > 0 && <LocationMap reports={reports} selected={selected} onSelect={setSelected}/>}
        <div aria-busy={busy} className="workspace">
          <section className="inbox-panel" aria-label="SOS inbox">
            {reports.length > 0 ? <RequestList reports={reports} selected={selected} onSelect={setSelected} now={now}/> : <div className="empty-state"><span className="empty-symbol" aria-hidden="true">◎</span><h2>No requests received yet</h2><p>Cloud-consented SOS reports will appear after a gateway uploads them.</p></div>}
            {nextBefore !== null && <div className="load-more"><button className="secondary-button" disabled={busy} onClick={() => void load(false, nextBefore)}>{busy ? "Loading…" : "Load older requests"}</button></div>}
          </section>
          {report ? <RequestDetails report={report} now={now}>{report.expires_at_ms > now && <FindHelp key={report.request_id} requestId={report.request_id}
            token={session.token} operator={session.role === "operator"} onAccessLost={message => { clearSession(); setError(message); }}/>}</RequestDetails>
            : <aside className="detail-panel detail-empty"><div className="eyebrow">Request details</div><p>Select a request to read its full report.</p></aside>}
        </div>
        </>}
      </>}
      <footer><span>GHOST / Nearby phones. Shared reach.</span><span>Server receipt confirms storage, not assistance.</span></footer>
    </main>
  </>;
}
