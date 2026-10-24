import { useEffect, useMemo, useRef, useState } from "react";
import type * as Leaflet from "leaflet";
import type { SosReport } from "./api";
import { categoryName, closed } from "./Requests";

type LocatedReport = SosReport & { latitude: number; longitude: number };
type MapView = { library: typeof Leaflet; map: Leaflet.Map };

export function LocationMap({ reports, selected, onSelect }: {
  reports: SosReport[];
  selected: string | null;
  onSelect: (id: string) => void;
}) {
  const located = useMemo(() => reports.filter((report): report is LocatedReport =>
    !closed(report) && typeof report.latitude === "number" && Number.isFinite(report.latitude) && Math.abs(report.latitude) <= 90 &&
    typeof report.longitude === "number" && Number.isFinite(report.longitude) && Math.abs(report.longitude) <= 180), [reports]);
  const container = useRef<HTMLDivElement>(null);
  const [view, setView] = useState<MapView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const hasLocations = located.length > 0;

  useEffect(() => {
    if (!hasLocations || !container.current) return;
    let disposed = false;
    let map: Leaflet.Map | undefined;
    setError(null);
    // Leaflet needs a browser DOM. Load it only while the authenticated map is mounted.
    void import("leaflet").then(library => {
      if (disposed || !container.current) return;
      map = library.map(container.current, { scrollWheelZoom: false });
      library.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
        maxZoom: 19,
        attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
      }).on("tileerror", () => {
        if (!disposed) setError("Some map tiles could not load. Check your internet connection; request locations are still listed below.");
      }).addTo(map);
      setView({ library, map });
    }).catch(() => { if (!disposed) setError("The map could not load. Request locations are still listed below."); });
    return () => { disposed = true; map?.remove(); setView(null); };
  }, [hasLocations]);

  useEffect(() => {
    if (!view || view.map.getContainer() !== container.current || !located.length) return;
    view.map.fitBounds(view.library.latLngBounds(located.map(report => [report.latitude, report.longitude])),
      { padding: [30, 30], maxZoom: 15 });
  }, [view, located]);

  useEffect(() => {
    if (!view || view.map.getContainer() !== container.current) return;
    const { library, map } = view;
    const markers = library.layerGroup().addTo(map);
    for (const report of located) {
      const label = `${categoryName(report.category)} SOS at ${report.latitude}, ${report.longitude}`;
      const marker = library.marker([report.latitude, report.longitude], {
        title: label, alt: label,
        icon: library.divIcon({ className: `map-marker${selected === report.request_id ? " selected" : ""}`,
          html: "<span></span>", iconSize: [26, 26], iconAnchor: [13, 13] }),
      }).on("click", () => onSelect(report.request_id)).addTo(markers);
      marker.getElement()?.setAttribute("aria-label", label);
      marker.getElement()?.setAttribute("aria-pressed", String(selected === report.request_id));
    }
    return () => { markers.remove(); };
  }, [view, located, selected, onSelect]);
  return <section className="map-panel" aria-label="SOS location map">
    <div className="map-heading">
      <div><div className="eyebrow">Server locations</div><h2>SOS location map</h2></div>
      <span className="subtle">{located.length} of {reports.length} requests with GPS</span>
    </div>
    {hasLocations ? <>
      <div ref={container} className="map-frame" aria-label="Interactive SOS location map"/>
      {error && <p className="error" role="status">{error}</p>}
      <div className="map-locations" aria-label="Requests with GPS">{located.map(report =>
        <button key={report.request_id} className="secondary-button" onClick={() => {
          onSelect(report.request_id);
          view?.map.panTo([report.latitude, report.longitude]);
        }} aria-label={`${categoryName(report.category)} SOS at ${report.latitude}, ${report.longitude}`}
          aria-pressed={selected === report.request_id}>
          {categoryName(report.category)} · {report.latitude.toFixed(5)}, {report.longitude.toFixed(5)}
        </button>)}</div>
    </> : <div className="map-empty"><p>No uploaded SOS report includes a GPS location.</p><span className="subtle">Locations appear only when the sender shared a recent fix.</span></div>}
    <p className="map-note">Shows loaded open requests only. Closed requests remain in the list. Select a marker to open its request. OpenStreetMap supplies the basemap, needs internet and receives requests for the viewed area.</p>
  </section>;
}
