# GHOST

Goal: ask for help during outages. A phone saves an SOS, nearby phones relay it,
and an online gateway uploads it. The server matches requests with helpers/supplies;
a web operator approves proposals, resource owners accept/decline, and signed
responses return through the mesh. Without internet, cloud coordination waits.

Stack: one Kotlin/Compose app with Room/Nearby, one Fastify server with
PostgreSQL with OpenAI or Gemini, and a React/Vite dashboard. API and worker share one service.

Built: SOS forms/drafts and signed reports in Room, local-only saves, and one-tap
mesh join with automatic Nearby connections/reconnection. Foreground SOS relay
has a four-transfer budget, bounded per-phone queues, receipt-driven retries and
visible relay status.
Network shows direct and recently reported remote links with dragging, pinch zoom
and details on tap. A 1–4 hop slider or All known filters the view without changing
SOS relay. Full screen expands the graph while keeping its selection and view.
Signed topology updates expire after one minute, stay in memory, and are bounded
to four transfers and 64 known reports. Node colours show reported internet state;
G marks a configured gateway, with recent server reachability shown separately.
This is a partial known-network view, never proof of a working route or delivery.
Signed neighbor receipts and delivery knowledge survive restarts. Local-only
reports stay private to the device; original signatures and expiry are preserved.
Verified duplicate SOS arriving by a shorter route improve the stored forwarding
budget, preserving signed bytes, expiry, cloud state and all neighbor receipts.
Signing, cloud-key pinning, consent and server authentication also work.
SOS sending needs only a message and Send; optional details and remembered sharing
settings expand in place. GPS sharing is on by default but optional and remembered
after sending; a cached location is included only with permission and a valid fix
no older than five minutes, measured with Android's monotonic clock. Mesh joining
alone never creates an SOS. Connections
are open without manual code verification; signatures do not prove human identity.
The user reports the mesh works on physical phones; handset key persistence has
not been separately verified here.
Server SOS uploads now persist in PostgreSQL with consent/signature checks,
transactional deduplication and stored signed cloud acknowledgements. Local
embedded-PostgreSQL tests pass; external deployment has not been verified.
Connect to GHOST now joins nearby phones and automatically connects online over
cellular or Wi-Fi while foregrounded. Hosted servers use Android's default route,
including VPNs; private LAN servers use available Wi-Fi. Builds include the server URL and trusted public key;
phones prove their existing Keystore identity with signed one-use challenges and
receive renewable upload-only sessions. Credentials stay encrypted on the phone;
the server stores their hashes. Manual gateway settings are legacy technical details.
Only consented reports upload; verified server acknowledgements persist in Room.
Enrollment reuses pending challenges without invalidating another caller's proof.
Shared-network enrollment allows 600 requests/minute per IP, removing the old 15-phone bottleneck;
separate persistent packet budgets limit uploads across device renewal and servers.
Throttled uploads stay queued for retry. Handshake, cancellation, renewal,
cellular/LAN routing, offline/foreground gating and upload tests pass locally;
physical one-click enrollment and credential persistence still need device testing.
Cloud acknowledgements now return through the mesh with their own durable queue,
expiry and four-transfer budget. The original phone shows verified server receipt.
Return routing has host exchange/restart tests; physical cloud-to-mesh testing remains.
Android uses plain-language actions and delivery updates; connection diagnostics
sit under optional Technical details.
The web SOS desk lists server-received reports with request details, pagination,
manual refresh and expiry labels. Its authenticated Leaflet map plots shared GPS
locations with pan/zoom and links markers to request details. OpenStreetMap tiles
need internet and reveal the viewed area to the tile provider, without sending
SOS text or access tokens. Viewer/operator tokens stay in tab memory; gateway tokens
cannot read it. Local browser checks pass against signed uploads.
Navigation is Home, Requests, Offer help and Connections. Requests shows each SOS's
upload status and an optional explanation; the old Help status tab is removed.
Request owners can cancel an SOS or mark it resolved after confirmation. Signed
terminal closures retain the original expiry/consent and carry ownership proof,
so they can arrive first without a delayed original reopening the request.
Closures have durable four-transfer relay queues and verified server receipts;
private SOS stay private. Room upgrades in place. Closed requests stop forwarding
and cloud AI scheduling; in-flight suggestions are rejected and older proposals
become stale. Dashboard labels closures and removes them from the active map.
Older/offline phones may retain open copies until delivery or expiry. Host tests
cover these paths; physical closure/gateway testing remains.
Offer help lists owned volunteer availability and supplies separately from forms,
and loads the saved volunteer name, skill, area and sharing choices when updating.
It saves signed volunteer profiles and supply offers in Room.
Publications relay to supporting phones and upload with separate online consent;
verified server receipts return through the mesh. Help types, area, quantity/unit
and six-hour availability appear in dashboard Helpers/Supplies lists. The latest
owner-signed helper report controls availability; supplies are separate offers.
Helper/supply relay scans the bounded saved queue before limiting each batch to
100 eligible packets, so exhausted, excluded or unsupported updates cannot block later help.
Existing phone data migrates in place. Local-only publications stay local.
Turning volunteer sharing off now queues a signed unavailable report for its previous
audience, omitting name/area and preserving the previous expiry. New private details
and sharing choices stay local. Withdrawals survive restarts, supersede old queued
availability and show their separate server receipt; offline copies wait for delivery
or expiry. Nearby availability can remain shared while online availability is withdrawn.
Owners can edit supply quantity/unit/area or withdraw the same offer. Signed full
updates carry the original ownership proof and keep its expiry and sharing flags;
latest-only forwarding and projections prevent delayed originals undoing changes.
Dashboard shows withdrawn offers. The coordinator should read resource_offer_latest
and exclude withdrawn (data.state = 2), expired or unavailable supplies.

The cloud AI coordinator now queues active SOS after uploads/restarts and on
availability changes. It uses current supply versions, validates bounded model
suggestions and stores proposals without allocating stock. Leases, bounded retries
and source checks guard processing. Dashboard AI suggestions shows runs and stale
proposals; operators can request a rerun. Select GHOST_AI_PROVIDER=openai (default)
or gemini, set its OPENAI_API_KEY/GEMINI_API_KEY and GHOST_COORDINATOR_MODEL on the
server. Both providers share validation and queues. A persistent shared budget
defaults to 120 provider calls per UTC hour, configurable on the server; exhausted
work stays queued for the next hour and the dashboard shows the pause. This limits
calls, not currency spend. Live provider testing remains.

Next: operator approval → owner decisions, allocation and signed assistance returns.

Keep public/cloud consent separate, preserve signed bytes, reject forged/duplicate
events, and keep cloud/provider secrets server-side. Queued, received and confirmed are distinct.
Private messaging comes last. Local AI and LoRa wait.
