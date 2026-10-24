import type { SosReport } from "./api";
import type { ReactNode } from "react";

const categories = ["Other", "Medical", "Trapped", "Water", "Food", "Shelter", "Other"];
export const categoryName = (category: number) => categories[category] ?? "Other";
export const time = (ms: number) => new Date(ms).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });

export function RequestDetails({ report, now, children }: { report: SosReport; now: number; children?: ReactNode }) {
  return <aside className="detail-panel" aria-label="Selected request">
    <div className="eyebrow">Request details</div>
    <h2>{categoryName(report.category)}</h2>
    <span className="badge received">Received by server</span>
    <p className="report-message">{report.description}</p>
    <dl className="details">
      <div><dt>Area</dt><dd>{report.coarse_area || "Not provided"}</dd></div>
      <div><dt>Landmark</dt><dd>{report.landmark || "Not provided"}</dd></div>
      <div><dt>GPS location</dt><dd>{report.latitude != null && report.longitude != null ? `${report.latitude}, ${report.longitude}` : "Not provided"}</dd></div>
      <div><dt>People needing help</dt><dd>{report.people_count ?? "Not provided"}</dd></div>
      <div><dt>Reported</dt><dd>{time(report.created_at_ms)}</dd></div>
      <div><dt>Received by server</dt><dd>{time(report.received_at_ms)}</dd></div>
      <div><dt>Sharing expires</dt><dd>{time(report.expires_at_ms)}{report.expires_at_ms <= now && <span className="expired-note">Sharing window ended</span>}</dd></div>
      <div><dt>Request ID</dt><dd className="identifier">{report.request_id}</dd></div>
    </dl>
    <div className="help-note"><strong>Help is not confirmed.</strong><p>This receipt confirms server storage. AI suggestions require human review and owner acceptance.</p></div>
    {children}
  </aside>;
}

export function RequestList({ reports, selected, onSelect, now }: {
  reports: SosReport[]; selected: string | null; onSelect: (id: string) => void; now: number;
}) {
  return <div className="request-list" aria-label="Received SOS requests">
    <div className="list-columns" aria-hidden="true"><span>Request / area</span><span>People</span><span>Server received</span></div>
    {reports.map(report => <button key={report.request_id} className="request-row"
      aria-pressed={selected === report.request_id} onClick={() => onSelect(report.request_id)}>
      <span className="request-main"><span className="request-category">{categoryName(report.category)}
        {report.expires_at_ms <= now && <span className="badge expired">Sharing expired</span>}
      </span><span className="message-preview">{report.description}</span><span className="area">{report.coarse_area || "Area not provided"}</span></span>
      <span className="people"><span className="mobile-label">People: </span>{report.people_count ?? "—"}</span>
      <span className="received-time"><span className="mobile-label">Received: </span>{time(report.received_at_ms)}</span>
    </button>)}
  </div>;
}
