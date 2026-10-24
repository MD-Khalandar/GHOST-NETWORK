# GHOST

Offline SOS over nearby Android phones, with cloud-assisted help coordination.
Read [PROJECT.md](PROJECT.md) for the short product brief and current state.

## Android

Use JDK 21, Android SDK 36 and Build Tools 36.0.0. Set `JAVA_HOME` and
`ANDROID_HOME`, or use the local installers:

- Windows: `powershell -ExecutionPolicy Bypass -File scripts/setup-windows.ps1`
- Apple Silicon Mac: `./scripts/setup-macos.sh` (optional `--with-studio` / `--with-emulator`)
- Linux / Intel Mac: install the prerequisites through Android Studio.

Windows PowerShell:

```powershell
. .\scripts\env.ps1
.\gradlew.bat assembleDebug testDebugUnitTest
```

macOS / Linux:

```sh
source scripts/env.sh
./gradlew assembleDebug testDebugUnitTest
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. With a phone connected, use
`installDebug` and launch GHOST. SOS drafts, reports and queued packets are saved
locally. After Connect, a foreground service keeps nearby relay and gateway sync active when the app is backgrounded or the screen locks.
Configured gateway phones can upload reports with cloud consent.

For two phones with Google Play services:

1. Keep Bluetooth/Wi-Fi on. Open both apps to connect. Tap **Connect to GHOST** on each and allow Android's required permissions. Discovery, connections and reconnection are automatic; no phone selection or pairing approval.
2. Tap **Send SOS**, describe the help needed, then **Send SOS**. Category, area and a +/− people counter are under **Optional details**. There is no review screen.
3. Check Requests on the other phone. The sender shows receipt only after the receiver saves the report and returns a valid signature.

New SOS requests always enable nearby sharing and online help coordination; there
are no sharing checkboxes. The form explains that nearby people can read/relay the
request and a gateway can upload it. **Share GPS location if available** remains
optional and remembered. With permission, sending tries a current GPS/network fix
for up to five seconds, then falls back to a saved fix no older than five minutes.
If neither is available, the SOS sends without location. Turning GPS sharing off
skips collection. Pending location requests stop on completion, timeout or cancellation.
Older unfinished drafts use the same sharing policy when sent. Existing signed
reports keep their original consent. Joining alone never creates a new SOS;
previously queued public reports resume automatically.

In **Requests**, your own unexpired SOS has **Cancel SOS** and **Mark resolved**.
Confirm either action to end the request; send a new SOS if you need help again.
The signed closure keeps the original sharing choices and expiry, and can reach
other phones/server before the original SOS. Once received, it stops forwarding
the open request and excludes it from new cloud AI matching. The dashboard marks
older suggestions stale and removes the request from the active map.
The closure has its own delivery queue and verified server receipt. Local-only
requests stay local. Phones need the updated app to exchange closures; older or
offline copies may remain open until delivery or expiry. Marking resolved is the
requester's report, not independent confirmation that assistance was delivered.

To try a relay, send A's SOS to B, leave the mesh on A, then let B meet C. C should
receive A's unchanged report. Forwarding stops after four transfers or expiry.
Receipts confirm saving by the immediate neighbor. Each phone remembers which
neighbors already hold a report across restarts and reconnects; unacknowledged
sends remain eligible for retry. Duplicates are saved only once. Existing local
data upgrades in place. The user reports the mesh works on physical phones.

The mesh is open to nearby GHOST phones; connections have no manual code check.
Signing verifies report integrity and key possession, not a person's identity.
After **Connect to GHOST**, a connected-device foreground service keeps relay and
online sync active when switching apps, locking the screen or closing the activity.
The **GHOST is active** notification opens the app and has **Disconnect**; the app's
Disconnect button stops the same session. Android 13+ asks for notification
permission when connecting; denial does not block the service, but Android can hide
its notification from the drawer (use the app or Android's active-app controls).
Explicit disconnect, force-stop or process termination requires another Connect tap;
there is no automatic reboot startup. Manufacturer battery policies and deep idle
can still interrupt radios/network work; verify lock-screen behavior on real phones.
Connections' **Known network** graph shows direct neighbors and fresh connections
reported by other phones. Use the **1–4 hop slider** to see farther; it only filters
the display, or choose **All known** for the bounded discovered graph. **Full screen**
expands the graph; closing it keeps your selection, hop filter and view. Drag nodes,
pinch to zoom, tap for details or reset the view. Solid
lines are direct, ready connections; dashed lines are reported links or phones
still getting connected. This is a partial view, not proof of a route or delivery.

Green nodes report internet access, amber means internet was not detected, and
grey means unknown. **G** marks a configured gateway. Tap it to see whether that
phone recently reached the server; internet alone does not imply gateway access.
Statuses are reported by the originating phone, not independently verified here.
Topology reports expire after one minute, are kept only in memory, and stop after
four transfers. The view is capped at 64 phones. Joining advertises connection IDs
and connectivity status; it does not expose names, contacts, IP addresses or SOS text.
Older app versions still connect/relay SOS but do not share topology. For A–B–C,
update all three phones, connect them, and select 2 hops on A to see C.

Each phone has independent bounded queues. A report waits for its neighbor's
signed receipt before the next report goes out on that link. Missing receipts
trigger up to three attempts per connection; **Try sending again** resumes them
without forgetting saved receipts. Requests shows relay acknowledgements and
the remaining transfer budget. Expired reports are never queued for another send.
The relay also returns cloud-signed acknowledgements toward the original phone,
using a separate four-transfer budget and the acknowledgement's own expiry.
Phones can carry a return even without the original SOS. Signed bytes, hop
receipts and queued returns survive restarts; forwarding stops at the destination.

For one-click online connection, the server operator prepares the APK once. With
`server/.env` configured, run from the project root:

```sh
node scripts/configure-client.mjs
source scripts/env.sh
./gradlew assembleDebug
```

The script derives only the public key from the existing server key and picks the
Mac's current LAN address. It writes ignored `.local/phone-server-test/client.properties`;
it never changes server credentials or exports private keys. Re-run after a LAN
address change and rebuild. For a hosted deployment pass its stable HTTPS address:
`node scripts/configure-client.mjs https://your-ghost-server.example`.
Build properties `ghostServerUrl` and `ghostCloudPublicKey` (or environment variables
`GHOST_SERVER_URL` / `GHOST_CLOUD_PUBLIC_KEY`) override these local public settings.
Release builds require HTTPS and a nonempty trusted public key.

Install the same configured APK on participating phones. Tap **Connect to GHOST**,
allow first-use Android permissions. Connection work then continues through the foreground service. Nearby connections
start automatically; cellular or Wi-Fi enables server connection and device enrollment
without entering URLs or tokens. Hosted servers use Android's default connection,
including VPNs. A private LAN server uses available Wi-Fi; internet alone cannot
reach a private LAN address. Provider and server private keys stay
server-side. Dashboard/operator authentication remains separate.

The phone checks both enrollment replies against its trusted server key and proves
possession of its existing Android Keystore identity. An expiring gateway-only
credential is stored encrypted and renewed automatically, including after a 401.
Disconnecting or losing all usable networks pauses online transfers; reconnecting
resumes them. New publications enable both sharing flags; uploads still require those signed flags, and **Received by server** requires a verified, saved acknowledgement.
No connection status creates an SOS or confirms assistance. Manual gateway tokens
remain available only under **Connections → Technical details** in legacy builds
without bundled automatic connection settings. Wire definitions remain in
`contracts/ghost.proto`; fixtures contain test-only keys.

## Helpers and supplies

From Home, tap **Offer help**. Choose **Volunteer** (help type, optional name and
area, available/unavailable) or **Supplies** (resource type, optional quantity,
unit and area). Save before sharing; availability expires after six hours.
New volunteer publications and supply offers always share nearby and allow online
coordination. There are no sharing checkboxes. Saving alone does not assign help or
reserve stock. Volunteers can mark themselves unavailable; supply owners can
withdraw offers. Updates wait for delivery to other phones/server, with a separate
verified receipt. Existing signed offers retain their original audience when edited;
create a new offer to broaden an older offer's sharing.

Updated phones relay publications with the same four-transfer limit, signed
neighbor receipts and gateway upload/return path as SOS. Older phones continue
handling SOS; helper/supply relay requires updated peers. The form lists your
saved help and shows **Received by server** only after verifying the receipt.
Supply offers also appear in Requests. Existing Room data upgrades without reset.

The dashboard's **Helpers** and **Supplies** tabs show uploaded owner-reported
availability with expiry and pagination. A newer shared volunteer report replaces
the previous profile, including marking yourself unavailable. Each supply save is
a separate offer. Under your saved supplies, **Edit offer** changes quantity,
unit or area and **Withdraw offer** marks it unavailable. Edits keep the original
offer ID, type, expiry and sharing choices. Latest changes relay and upload with
their own verified receipts; a local withdrawal is still waiting until it reaches
other phones/server. Updates carry the original ownership proof, so arrival order
doesn't revive withdrawn stock. All phones carrying edits need the updated app;
offline/older copies may remain stale until updated or expired. Approval and owner decisions use the assistance flow below.

## Server

See [server/README.md](server/README.md). Temporary tools/caches belong in ignored
`.tmp/` or `.local/`; credentials and build outputs stay out of Git.

## SOS dashboard

With Node.js 22.12+ and the server configured:

```sh
npm --prefix dashboard ci
npm --prefix dashboard run build
npm --prefix server run build
npm --prefix server start
```

Create a dashboard account once with `server/.env` configured:

```sh
npm --prefix server run account -- --username admin --role operator
```

Enter a password of at least 15 characters when prompted; it stays hidden. Use
`--role viewer` for read-only access. Running the command again resets that account
and signs out its existing sessions. Production also needs
`GHOST_DASHBOARD_ORIGIN=https://<your-host>` on the server.

Open `http://127.0.0.1:3000/dashboard/` and sign in with your username and password.
It lists uploaded SOS reports and plots
reports with shared GPS coordinates on the built-in server map. Select a marker
to open its request details; reports without coordinates remain in the list but
are not plotted. Leaflet provides pan/zoom and clickable markers over OpenStreetMap
tiles. The basemap needs internet; tile requests reveal the viewed map area to
OpenStreetMap but do not send SOS text or access tokens. Refresh checks for new arrivals;
older reports load in pages. Expiry does not mean help was provided. A protected
cookie keeps you signed in across refreshes and browser restarts for up to seven
days; 24 hours of inactivity ends the session. **Sign out** revokes access on the
server. Use HTTPS in deployment. Phone enrollment remains automatic and separate.

For UI development, run `npm --prefix dashboard run dev` alongside the server;
open `http://127.0.0.1:5173/dashboard/`. Vite proxies `/v1` to port 3000. If setting
`GHOST_DASHBOARD_ORIGIN` for this mode, use `http://127.0.0.1:5173`, the browser origin.
Checks: `npm --prefix dashboard test` and `npm --prefix dashboard run build`.

## Cloud AI matching

For OpenAI (default), set `OPENAI_API_KEY` and `GHOST_COORDINATOR_MODEL` in the
server environment. For Gemini, use:

```dotenv
GHOST_AI_PROVIDER=gemini
GEMINI_API_KEY=<your Google AI Studio API key>
GHOST_COORDINATOR_MODEL=<Gemini model ID available to your project>
```

Restart the server after configuration. Both providers use the same validated
suggestion tool; OpenAI uses Responses and Gemini uses Google's Chat Completions
compatibility endpoint. Keys stay server-side. No Android rebuild is needed.
New SOS and relevant availability changes trigger matching automatically. **AI suggestions**
shows model suggestions, run progress/failures and outdated results. You can
page through older suggestions or filter by SOS ID; the selected page keeps
refreshing. Run results distinguish missing candidates from no suitable matches
and saved suggestions. Relevant candidate changes and expiry trigger automatic
rechecks; unrelated uploads and delayed originals do not rerun unchanged inputs.
Operators can use **Find help with AI** on an SOS to request another run. Without model settings,
the dashboard reports AI unavailable and the existing SOS/relay flow still works.

Only currently available helpers/supplies are candidates. Signed supply edits and
withdrawals invalidate older proposals. Operators can **Approve invitation** or
**Reject suggestion**. Approval sends a signed invitation; it does not reserve stock.
A known supply quantity is required before approval (edit and republish unknown stock).

Owners receive invitations under **Offer help** and can **Accept request** or
**Decline request** after confirmation. Offline decisions remain queued until an
online gateway reaches the server. The server rechecks sources and reserves supplies
atomically, or rejects an outdated/conflicting acceptance. Volunteer commitments
prevent double booking. Invites expire within an hour, bounded by source availability.

Signed updates return directly or through supporting mesh peers. Requesters see
invitations and commitments in **Requests**; owners can **Cancel commitment** under
**Offer help**. All participating relay phones need this app version. Gateways poll
while the connection service is active; download cursors and signed packets persist in Room.
An owner tap, a server commitment and physical assistance are separate states.

After receiving the agreed help, the requester can tap **Confirm help received**
on that commitment under **Requests** and confirm. This completes only that
arrangement; the SOS stays open until cancelled or marked resolved. The signed
confirmation queues offline, and both phones/dashboard show **Help received
(confirmed by requester)** after the server records it. Supplies count as used;
volunteers become available again. All participating relay phones need this version.
This records the requester's report, not independent evidence of delivery.

Supply cards show last-known available/reserved/used quantities. Edits preserve
committed amounts and units; offline phones may learn newer reservations later, so
the server checks again. **Withdraw offer** stops new matching once delivered and
keeps existing commitments; cancel those separately if you cannot fulfil them.
Cancelling an SOS releases active reservations. Marking it resolved records its
committed supplies as used and frees volunteers; it is a requester report, not
independent proof of physical delivery. Expired/cancelled invitations cannot reopen
an arrangement when an old packet arrives.
