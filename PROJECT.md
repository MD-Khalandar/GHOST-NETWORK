# GHOST

Goal: ask for help during outages. A phone saves an SOS, nearby phones relay it,
and an online gateway uploads it. The server matches requests with helpers/supplies;
a web operator approves proposals, resource owners accept/decline, and signed
responses return through the mesh. Without internet, cloud coordination waits.

Stack: one Kotlin/Compose app with Room/Nearby, one Fastify server with
PostgreSQL/OpenAI, and a React/Vite dashboard. API and worker share one service.

Built: SOS forms/drafts and signed reports in Room, local-only saves, and one-tap
mesh join with automatic Nearby connections/reconnection. Foreground SOS relay
has a four-transfer budget, bounded per-phone queues, receipt-driven retries and
visible relay status.
Network shows a dark, floating graph of direct neighbors with dragging, pinch zoom
and connection details on tap, including phones still checking identity. It does
not infer the wider mesh or relay routes.
Signed neighbor receipts and delivery knowledge survive restarts. Local-only
reports stay private to the device; original signatures and expiry are preserved.
Signing, cloud-key pinning, consent and server authentication also work.
SOS sending needs only a message and Send; optional details and remembered sharing
settings expand in place. Mesh joining alone never creates an SOS. Connections
are open without manual code verification; signatures do not prove human identity.
The user reports the mesh works on physical phones; handset key persistence has
not been separately verified here.
Server SOS uploads now persist in PostgreSQL with consent/signature checks,
transactional deduplication and stored signed cloud acknowledgements. Local
embedded-PostgreSQL tests pass; external deployment has not been verified.
Configured Android gateways upload cloud-consented SOS reports while joined and
foregrounded, retry failures, and persist verified cloud acknowledgements in Room.
Gateway tokens use Keystore-encrypted settings. Local HTTP tests pass; deployed
phone-to-cloud behavior and gateway credential persistence need device testing.
Cloud acknowledgements now return through the mesh with their own durable queue,
expiry and four-transfer budget. The original phone shows verified server receipt.
Return routing has host exchange/restart tests; physical cloud-to-mesh testing remains.
Android uses plain-language actions and delivery updates; connection diagnostics
sit under optional Technical details.
The web SOS desk lists server-received reports with request details, pagination,
manual refresh and expiry labels. Viewer/operator tokens stay in tab memory;
gateway tokens cannot read it. Local browser checks pass against signed uploads.

Next: helper/supply records and matching → approval, owner decisions and outage recovery.

Keep public/cloud consent separate, preserve signed bytes, reject forged/duplicate
events, and keep cloud/provider secrets server-side. Queued, received and confirmed are distinct.
Chat, local AI and LoRa wait.
