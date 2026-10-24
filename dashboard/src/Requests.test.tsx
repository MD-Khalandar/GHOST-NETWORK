import assert from "node:assert/strict";
import test from "node:test";
import { renderToStaticMarkup } from "react-dom/server";
import type { SosReport } from "./api";
import { RequestDetails, RequestList } from "./Requests";

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
