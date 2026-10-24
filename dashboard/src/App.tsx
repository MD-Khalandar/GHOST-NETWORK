import { useEffect, useRef, useState, type FormEvent } from "react";
import { ApiError, get, login, logout, type Session, type SosPage, type SosReport } from "./api";
import { RequestDetails, RequestList, time, closed } from "./Requests";
import Resources from "./Resources";
import Coordination, { FindHelp } from "./Coordination";
import { LocationMap } from "./LocationMap";

export default function App() {
  // The browser holds an HttpOnly session cookie; only the CSRF token is available to this UI.
  const [session, setSession] = useState<Session | null>(null);
  const [tab, setTab] = useState<"sos" | "helpers" | "offers" | "ai">("sos");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [restoring, setRestoring] = useState(true);
  const [signingOut, setSigningOut] = useState(false);
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
    setSession(null); setPassword(""); setReports([]); setNextBefore(null); setSelected(null); setUpdated(null); setBusy(false);
    setTab("sos");
  }

  async function load(phase: "refresh" | "login" | "restore" = "refresh", before?: number) {
    pending.current?.abort();
    const controller = new AbortController(); pending.current = controller;
    setBusy(true); setError(null);
    try {
      const identity = phase === "login" ? await login(username.trim(), password, controller.signal) :
        phase === "restore" ? await get<Session>("auth/session", controller.signal) : session!;
      if ((identity.role !== "viewer" && identity.role !== "operator") || !identity.csrf_token) throw new ApiError(403);
      if (pending.current !== controller) return;
      setSession(identity); setPassword("");
      const page = await get<SosPage>(`dashboard/sos?limit=30${before === undefined ? "" : `&before=${before}`}`, controller.signal);
      if (pending.current !== controller) return;
      setReports(previous => before === undefined ? page.items : [...previous, ...page.items.filter(item => !previous.some(old => old.request_id === item.request_id))]);
      setNextBefore(page.next_before);
      if (before === undefined) {
        setSelected(previous => page.items.some(item => item.request_id === previous) ? previous : page.items[0]?.request_id ?? null);
        setUpdated(Date.now());
      }
      setNow(Date.now());
    } catch (failure) {
      if (pending.current !== controller) return;
      if (phase === "restore") setRestoring(false);
      if (failure instanceof ApiError && [401, 403].includes(failure.status)) clearSession();
      if (!(phase === "restore" && failure instanceof ApiError && failure.status === 401))
        setError(failure instanceof ApiError ? failure.message : "Couldn't reach the server. Check your connection and try again.");
    } finally {
      if (pending.current === controller) {
        pending.current = null; setBusy(false);
        if (phase === "restore") setRestoring(false);
      }
    }
  }

  useEffect(() => { void load("restore"); return () => { pending.current?.abort(); pending.current = null; }; }, []);
  async function signOut() {
    if (!session || signingOut) return;
    pending.current?.abort(); const controller = new AbortController(); pending.current = controller;
    setSigningOut(true); setBusy(true); setError(null);
    try { await logout(session.csrf_token, controller.signal); clearSession(); }
    catch (failure) {
      if (pending.current !== controller) return;
      if (failure instanceof ApiError && failure.status === 401) clearSession();
      else setError("Could not sign out on the server. Check your connection and try again.");
    } finally { if (pending.current === controller) { pending.current = null; setBusy(false); } setSigningOut(false); }
  }
  function signIn(event: FormEvent) { event.preventDefault(); void load("login"); }
  const report = reports.find(item => item.request_id === selected);

  return <>
    <header className="topbar"><div className="brand"><svg viewBox="0 0 32 32" aria-hidden="true"><path d="M5 23V9l11 7L27 9v14l-11-7z" fill="none" stroke="currentColor" strokeWidth="2"/><circle cx="16" cy="16" r="3" fill="currentColor"/></svg><span>GHOST</span><span className="brand-divider"/><span className="desk-label">SOS desk</span></div>
      {session && <div className="session"><span>{session.username} <span className="role">/ {session.role}</span></span><button className="quiet-button" disabled={signingOut} onClick={() => void signOut()}>{signingOut ? "Signing out..." : "Sign out"}</button></div>}
    </header>
    <main>
      <div className="page-heading"><div><div className="eyebrow">{session ? "Server inbox" : "GHOST coordination"}</div><h1>{tab === "ai" ? "AI suggestions" : tab === "helpers" ? "People who can help" : tab === "offers" ? "Available supplies" : "Requests for help"}<span className="heading-dot">.</span></h1><p>See requests and availability that have reached the server.</p></div><span className="read-only">{session?.role === "operator" ? "Operator desk" : "Read-only desk"}</span></div>
      {session && tab !== "sos" && error && <p className="error error-banner" role="alert">{error}</p>}
      {restoring ? <section className="sign-in-panel" aria-busy="true"><p role="status">Restoring your session...</p></section> : !session ?
        <SignInForm username={username} password={password} busy={busy} error={error}
          onUsername={setUsername} onPassword={setPassword} onSubmit={signIn}/> : <>
        <nav className="desk-tabs" aria-label="Desk sections">{([['sos', 'SOS requests'], ['helpers', 'Helpers'], ['offers', 'Supplies'], ['ai', 'AI suggestions']] as const).map(([value, title]) =>
          <button key={value} className="secondary-button" aria-current={tab === value ? "page" : undefined} onClick={() => setTab(value)}>{title}</button>)}</nav>
        {tab === "ai" ? <Coordination operator={session.role==="operator"} csrfToken={session.csrf_token} onAccessLost={message => { clearSession(); setError(message); }}/>
          : tab !== "sos" ? <Resources key={tab} kind={tab} onAccessLost={message => { clearSession(); setError(message); }}/> : <>
        <div className="inbox-toolbar"><div><strong>{reports.length} {reports.length === 1 ? "request" : "requests"} loaded</strong><span className="subtle">{updated ? `Last refreshed ${time(updated)}` : "Loading requests"}</span></div><button className="secondary-button" disabled={busy} onClick={() => void load()}>{busy ? "Loading…" : "Refresh requests"}</button></div>
        <div className="inbox-note"><span className="status-dot" aria-hidden="true"/><p>Only uploaded reports appear here. Phones still offline may hold other requests. Refresh to check for new arrivals.</p></div>
        {error && <div className="error error-banner" role="alert">{error} {reports.length > 0 && "Showing previously loaded reports."}</div>}
        {reports.length > 0 && <LocationMap reports={reports} selected={selected} onSelect={setSelected}/>}
        <div aria-busy={busy} className="workspace">
          <section className="inbox-panel" aria-label="SOS inbox">
            {reports.length > 0 ? <RequestList reports={reports} selected={selected} onSelect={setSelected} now={now}/> : <div className="empty-state"><span className="empty-symbol" aria-hidden="true">◎</span><h2>No requests received yet</h2><p>Cloud-consented SOS reports will appear after a gateway uploads them.</p></div>}
            {nextBefore !== null && <div className="load-more"><button className="secondary-button" disabled={busy} onClick={() => void load("refresh", nextBefore)}>{busy ? "Loading…" : "Load older requests"}</button></div>}
          </section>
          {report ? <RequestDetails report={report} now={now}>{report.expires_at_ms > now && !closed(report) && <FindHelp key={report.request_id} requestId={report.request_id}
            csrfToken={session.csrf_token} operator={session.role === "operator"} onAccessLost={message => { clearSession(); setError(message); }}/>}</RequestDetails>
            : <aside className="detail-panel detail-empty"><div className="eyebrow">Request details</div><p>Select a request to read its full report.</p></aside>}
        </div>
        </>}
      </>}
      <footer><span>GHOST / Nearby phones. Shared reach.</span><span>Server receipt confirms storage, not assistance.</span></footer>
    </main>
  </>;
}

export function SignInForm({username,password,busy,error,onUsername,onPassword,onSubmit}: {
  username:string;password:string;busy:boolean;error:string|null;
  onUsername:(value:string)=>void;onPassword:(value:string)=>void;onSubmit:(event:FormEvent)=>void;
}) {
  return <section className="sign-in-panel" aria-labelledby="sign-in-title"><div><div className="section-number">01 / ACCESS</div>
    <h2 id="sign-in-title">Open your desk</h2><p>Sign in with the account supplied by your server administrator.</p>
    <p className="subtle">This browser stays signed in for up to seven days. A day of inactivity ends the session; sign out on shared computers.</p></div>
    <form onSubmit={onSubmit}>
      <label htmlFor="username">Username</label><input id="username" name="username" value={username}
        onChange={event=>onUsername(event.target.value)} required minLength={3} maxLength={64} autoComplete="username" autoCapitalize="none" spellCheck={false} disabled={busy}/>
      <label htmlFor="password">Password</label><input id="password" name="password" type="password" value={password}
        onChange={event=>onPassword(event.target.value)} required maxLength={256} autoComplete="current-password" disabled={busy} aria-describedby={error?"sign-in-error":undefined}/>
      {error && <p id="sign-in-error" className="error" role="alert">{error}</p>}
      <button className="primary-button" disabled={busy}>{busy?"Signing in...":"Sign in"}</button>
    </form></section>;
}
