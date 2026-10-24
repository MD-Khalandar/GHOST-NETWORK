# GHOST

Goal: ask for help during outages. A phone saves an SOS, nearby phones relay it,
and an online gateway uploads it. The server matches requests with helpers/supplies;
a web operator approves proposals, resource owners accept/decline, and signed
responses return through the mesh. Without internet, cloud coordination waits.

Stack: one Kotlin/Compose app with Room/Nearby, one Fastify server with
PostgreSQL with OpenAI or Gemini, and a React/Vite dashboard. API and worker share one service.

Built: SOS forms/drafts and signed reports in Room, local-only saves, and one-tap
mesh join with automatic Nearby connections/reconnection. SOS relay
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
SOS sending needs only a message and Send. New SOS, volunteers and supply offers
always enable nearby sharing and online coordination, without sharing checkboxes.
Older unsigned SOS drafts adopt these settings; existing signed records retain their
original audience. Supply edits keep the signed audience; a new offer broadens it.
GPS sharing is on by default but optional and remembered
after sending. Sending requests a current GPS/network fix for up to five seconds,
then falls back to a permitted valid cached fix no older than five minutes, measured
with Android's monotonic clock. Missing permission/location never prevents sending;
completion, timeout or cancellation stops location requests. Mesh joining
alone never creates an SOS. Connections
are open without manual code verification; signatures do not prove human identity.
Location tests cover fresh acquisition on Android 7/15, timeout fallback, cancellation
and missing permission/providers; handset GPS acquisition still needs field testing.
The user reports the mesh works on physical phones; handset key persistence has
not been separately verified here.
Server SOS uploads now persist in PostgreSQL with consent/signature checks,
transactional deduplication and stored signed cloud acknowledgements. Local
embedded-PostgreSQL tests pass; external deployment has not been verified.
Connect to GHOST now joins nearby phones and automatically connects online over
cellular or Wi-Fi while its foreground connection service is active. Hosted servers use Android's default route,
including VPNs; private LAN servers use available Wi-Fi. Builds include the server URL and trusted public key;
phones prove their existing Keystore identity with signed one-use challenges and
receive renewable gateway-only sessions. Credentials stay encrypted on the phone;
the server stores their hashes. Manual gateway settings are legacy technical details.
Only consented reports upload; verified server acknowledgements persist in Room.
Enrollment reuses pending challenges without invalidating another caller's proof.
Shared-network enrollment allows 600 requests/minute per IP, removing the old 15-phone bottleneck;
separate persistent packet budgets limit uploads across device renewal and servers.
Throttled uploads stay queued for retry. Handshake, cancellation, renewal,
cellular/LAN routing, offline/connection gating and upload tests pass locally;
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
SOS text or credentials. Viewer/operator accounts use username/password sign-in
and persistent HttpOnly cookies; sessions last up to seven days with a one-day idle
timeout. A local server command creates/resets/disables accounts; passwords use
scrypt, resets revoke sessions, and sign-out revokes server access. Production
requires a configured HTTPS dashboard origin; browser mutations check origin/CSRF.
Login budgets persist in PostgreSQL. Gateway tokens cannot read the desk, and
browser sessions cannot sync as phones. Legacy API bearer access remains supported.
Session, permissions, expiry and restart tests pass locally. A local browser check
also covers login, refresh/browser restart restoration and persistent sign-out.
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
and loads the saved volunteer name, skill and area when updating.
It saves signed volunteer profiles and supply offers in Room.
Publications relay to supporting phones and upload with separate online consent;
verified server receipts return through the mesh. Help types, area, quantity/unit
and six-hour availability appear in dashboard Helpers/Supplies lists. The latest
owner-signed helper report controls availability; supplies are separate offers.
Helper/supply relay scans the bounded saved queue before limiting each batch to
100 eligible packets, so exhausted, excluded or unsupported updates cannot block later help.
Existing phone data migrates in place. Local-only publications stay local.
Older signed withdrawals remain supported. Current volunteer updates publish their
available/unavailable state to nearby phones and online coordination. Changes wait
for delivery; offline copies may remain until updated or expired.
Owners can edit supply quantity/unit/area or withdraw the same offer. Signed full
updates carry the original ownership proof and keep its expiry and sharing flags;
latest-only forwarding and projections prevent delayed originals undoing changes.
Dashboard shows withdrawn offers. The coordinator should read resource_offer_latest
and exclude withdrawn (data.state = 2), expired or unavailable supplies.

The cloud AI coordinator queues active SOS after uploads/restarts and when their
bounded candidate inputs change, including expiry; unrelated uploads and old replays
do not rerun unchanged inputs. It filters help/supply types by SOS category, excludes
self matches and ranks normalized reported areas; unknown/other areas remain eligible.
Unspecified SOS categories retain all help types. It uses current supply versions,
validates bounded model suggestions and stores proposals without allocating stock. Leases, bounded retries
and source checks guard processing. Dashboard AI suggestions shows runs and stale
proposals with pagination, SOS filtering and a shared snapshot for freshness checks.
Run results distinguish no eligible resources, no suitable suggestions and saved
suggestions, with retry information and accurate queue totals. Operators can request
a rerun. Select GHOST_AI_PROVIDER=openai (default) or gemini, set its
OPENAI_API_KEY/GEMINI_API_KEY and GHOST_COORDINATOR_MODEL on the
server. Both providers share validation and queues. A persistent shared budget
defaults to 120 provider calls per UTC hour, configurable on the server; exhausted
work stays queued for the next hour and the dashboard shows the pause. This limits
calls, not currency spend. Live provider testing remains.

Assistance now supports operator approval/rejection, signed invitations, owner
accept/decline/cancel decisions and server-confirmed commitments. Gateways download
signed snapshots and relay them to owners/requesters; decisions travel back through
the same durable mesh/upload path. Room upgrades in place. Conflicting acceptances
cannot overbook supplies or volunteers; AI uses remaining availability. Supply
cards show last-known available/reserved/used amounts. Edits preserve committed
stock and units. Withdrawal stops new matching without cancelling commitments.
SOS cancellation releases active reservations; requester-reported resolution counts
supplies as used and frees volunteers. Invitations expire within an hour/source
expiry. Local signatures, Room/mesh/HTTP, concurrency, rollback and restart tests
cover the loop. Physical three-phone and deployed PostgreSQL testing remain.

Requesters can confirm receipt of each active commitment after a confirmation dialog.
An owner cannot confirm their own delivery. The signed decision queues offline and
returns through the existing mesh/gateway path; pending is distinct from recorded
receipt. Server state 7 records requester-confirmed receipt, counts supplies as used,
frees volunteers and leaves the SOS open. Replays cannot consume stock twice;
later SOS closure/expiry cannot release consumed stock. Existing server databases
upgrade in place; participating phones need this version to understand receipt updates.

Connect now starts a connected-device foreground service with a persistent GHOST
notification and Disconnect action. One process-wide runtime is shared by activities
and the service; switching apps, locking or destroying a screen no longer stops
relay, enrollment, uploads or assistance polling. Disconnect/service destruction
stops radio work and gates sync. Notification denial does not block connecting;
Android may hide the drawer notification. Process death/force-stop/reboot needs a
new Connect tap. Service/activity/notification lifecycle tests pass locally; handset
battery/lock-screen behavior still needs testing.

Next: field-test assistance/background connections and GPS acquisition. Server
commitment, requester-reported resolution and receipt do not independently prove delivery.

Validate signed sharing consent, preserve signed bytes, reject forged/duplicate
events, and keep cloud/provider secrets server-side. Queued, received and confirmed are distinct.
Private messaging comes last. Local AI and LoRa wait.
