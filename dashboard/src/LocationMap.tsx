import type { SosReport } from "./api";
import { categoryName } from "./Requests";

const WORLD_BOUNDS = { minLatitude: -90, maxLatitude: 90, minLongitude: -180, maxLongitude: 180 };

function boundsFor(reports: SosReport[]) {
  const located = reports.filter(report => report.latitude != null && report.longitude != null);
  if (!located.length) return WORLD_BOUNDS;
  const latitudes = located.map(report => report.latitude!);
  const longitudes = located.map(report => report.longitude!);
  const latitudeSpan = Math.max(Math.max(...latitudes) - Math.min(...latitudes), 0.01);
  const longitudeSpan = Math.max(Math.max(...longitudes) - Math.min(...longitudes), 0.01);
  const latitudePadding = Math.max(latitudeSpan * 0.15, 0.5);
  const longitudePadding = Math.max(longitudeSpan * 0.15, 0.5);
  return {
    minLatitude: Math.max(-90, Math.min(...latitudes) - latitudePadding),
    maxLatitude: Math.min(90, Math.max(...latitudes) + latitudePadding),
    minLongitude: Math.max(-180, Math.min(...longitudes) - longitudePadding),
    maxLongitude: Math.min(180, Math.max(...longitudes) + longitudePadding),
  };
}

function markerPosition(report: SosReport, bounds: ReturnType<typeof boundsFor>) {
  const longitudeSpan = bounds.maxLongitude - bounds.minLongitude || 1;
  const latitudeSpan = bounds.maxLatitude - bounds.minLatitude || 1;
  return {
    x: ((report.longitude! - bounds.minLongitude) / longitudeSpan) * 100,
    y: (1 - (report.latitude! - bounds.minLatitude) / latitudeSpan) * 100,
  };
}

export function LocationMap({ reports, selected, onSelect }: {
  reports: SosReport[];
  selected: string | null;
  onSelect: (id: string) => void;
}) {
  const located = reports.filter(report => report.latitude != null && report.longitude != null);
  const bounds = boundsFor(reports);
  return <section className="map-panel" aria-label="SOS location map">
    <div className="map-heading">
      <div><div className="eyebrow">Server locations</div><h2>SOS location map</h2></div>
      <span className="subtle">{located.length} of {reports.length} requests with GPS</span>
    </div>
    {located.length ? <div className="map-frame">
      <svg className="map-surface" viewBox="0 0 100 60" role="img"
        aria-label={`Map showing ${located.length} SOS locations`}>
        <rect width="100" height="60" className="map-water"/>
        <path d="M0 15H100M0 30H100M0 45H100M25 0V60M50 0V60M75 0V60" className="map-grid"/>
        <path d="M8 11l10-4 12 6 4 10-8 7-13-3-8-8zM43 8l12-4 13 8-4 10-10 4-8-7zM72 29l11-5 12 7-5 14-13 4-8-9zM25 39l10-5 13 6-4 12-16 3-8-8z" className="map-land"/>
        {located.map(report => {
          const position = markerPosition(report, bounds);
          return <button key={report.request_id} className={`map-marker${selected === report.request_id ? " selected" : ""}`}
            style={{ left: `${position.x}%`, top: `${position.y}%` }}
            onClick={() => onSelect(report.request_id)}
            aria-label={`${categoryName(report.category)} SOS at ${report.latitude}, ${report.longitude}`}
            aria-pressed={selected === report.request_id}>
            <span aria-hidden="true"/>
          </button>;
        })}
      </svg>
      <div className="map-axis map-axis-top"><span>{bounds.maxLatitude.toFixed(2)}° N</span><span>{bounds.minLatitude.toFixed(2)}°</span></div>
      <div className="map-axis map-axis-bottom"><span>{bounds.minLongitude.toFixed(2)}°</span><span>{bounds.maxLongitude.toFixed(2)}°</span></div>
    </div> : <div className="map-empty"><span className="empty-symbol" aria-hidden="true">◎</span><p>No uploaded SOS report includes a GPS location.</p><span className="subtle">Locations appear only when the sender shared a valid fix.</span></div>}
    <p className="map-note">Exact locations are visible only to this authenticated viewer/operator session. Selecting a marker opens its request.</p>
  </section>;
}
