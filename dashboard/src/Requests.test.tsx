import assert from "node:assert/strict";
import test from "node:test";
import { renderToStaticMarkup } from "react-dom/server";
import type { SosReport } from "./api";
import { RequestDetails, RequestList } from "./Requests";
import { LocationMap } from "./LocationMap";

test("reported text is escaped; expiry and missing details never imply assistance", () => {
  const report: SosReport = {
    request_id: "11111111-1111-4111-8111-111111111111", category: 3,
    description: "<script>alert('help')</script>", coarse_area: "<img src=x onerror=alert(1)>",
    landmark: null, people_count: null, created_at_ms: 1000, received_at_ms: 2000,
    expires_at_ms: 3000, cloud_revision: 1,
  };
  const details = renderToStaticMarkup(<RequestDetails report={report} now={4000}/>);
  const list = renderToStaticMarkup(<RequestList reports={[report]} selected={report.request_id} onSelect={() => {}} now={4000}/>);
  for (const html of [details, list]) {
    assert.doesNotMatch(html, /<script>|<img /);
    assert.match(html, /&lt;script&gt;/);
  }
  assert.match(details, /Not provided/); assert.match(details, /Help is not confirmed/);
  assert.match(details, /Sharing window ended/); assert.match(list, /Sharing expired/);
  assert.match(list, /aria-pressed="true"/);
  assert.doesNotMatch(renderToStaticMarkup(<RequestDetails report={report} now={2500}/>), /Sharing window ended/);
});

test("location map plots only reports with coordinates and exposes marker selection", () => {
  const located: SosReport = {
    request_id: "22222222-2222-4222-8222-222222222222", category: 2,
    description: "Medical help", coarse_area: null, landmark: null, people_count: 1,
    latitude: 12.5, longitude: 44.25, created_at_ms: 1000, received_at_ms: 2000,
    expires_at_ms: 3000, cloud_revision: 2,
  };
  const withoutLocation: SosReport = { ...located, request_id: "33333333-3333-4333-8333-333333333333", latitude: null, longitude: null };
  const invalidLocation: SosReport = { ...withoutLocation, latitude: NaN, longitude: 999 };
  const html = renderToStaticMarkup(<LocationMap reports={[located, withoutLocation, invalidLocation]}
    selected={located.request_id} onSelect={() => {}}/>);
  assert.match(html, /SOS location map/);
  assert.match(html, /1 of 3 requests with GPS/);
  assert.match(html, /aria-label="Trapped SOS at 12.5, 44.25"/);
  assert.match(html, /aria-pressed="true"/);
  assert.doesNotMatch(html, /33333333-3333-4333-8333-333333333333/);
  const empty = renderToStaticMarkup(<LocationMap reports={[withoutLocation]} selected={null} onSelect={() => {}}/>);
  assert.match(empty, /No uploaded SOS report includes a GPS location/);
});
