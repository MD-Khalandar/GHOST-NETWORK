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
Network shows direct and recently reported remote links with dragging, pinch zoom
and details on tap. A 1–4 hop slider or All known filters the view without changing
SOS relay. Full screen expands the graph while keeping its selection and view.
Signed topology updates expire after one minute, stay in memory, and are bounded
to four transfers and 64 known reports. Node colours show reported internet state;
G marks a configured gateway, with recent server reachability shown separately.
This is a partial known-network view, never proof of a working route or delivery.
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
Home's Offer help form saves signed volunteer profiles and supply offers in Room.
Publications relay to supporting phones and upload with separate online consent;
verified server receipts return through the mesh. Help types, area, quantity/unit
and six-hour availability appear in dashboard Helpers/Supplies lists. The latest
owner-signed helper report controls availability; supplies are separate offers.
Existing phone data migrates in place. Local-only publications stay local.
Owners can edit supply quantity/unit/area or withdraw the same offer. Signed full
updates carry the original ownership proof and keep its expiry and sharing flags;
latest-only forwarding and projections prevent delayed originals undoing changes.
Dashboard shows withdrawn offers. The coordinator should read resource_offer_latest
and exclude withdrawn (data.state = 2), expired or unavailable supplies.

The cloud AI coordinator now queues active SOS after uploads/restarts and on
availability changes. It uses current supply versions, validates bounded model
suggestions and stores proposals without allocating stock. Leases, bounded retries
and source checks guard processing. Dashboard AI suggestions shows runs and stale
proposals; operators can request a rerun. Enable with server-only OPENAI_API_KEY
and GHOST_COORDINATOR_MODEL. Live provider/deployment testing remains.

Next: operator approval → owner decisions, allocation and signed assistance returns.

Keep public/cloud consent separate, preserve signed bytes, reject forged/duplicate
events, and keep cloud/provider secrets server-side. Queued, received and confirmed are distinct.
Private messaging comes last. Local AI and LoRa wait.
