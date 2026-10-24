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
locally. Nearby runs while the app is open and can relay public SOS reports.
Configured gateway phones can upload reports with cloud consent.

For two phones with Google Play services:

1. Keep Bluetooth/Wi-Fi on and both apps open. Tap **Connect to GHOST** on each and allow Android's required permissions. Discovery, connections and reconnection are automatic; no phone selection or pairing approval.
2. Tap **Send SOS**, describe the help needed, then **Send SOS**. Category, area and a +/− people counter are under **Optional details**. There is no review screen.
3. Check Requests on the other phone. The sender shows receipt only after the receiver saves the report and returns a valid signature.

The form shows the sharing audience before sending. **Sharing settings** keeps
mesh sharing, GPS location sharing and cloud consent separate and remembers them
for the next SOS. GPS location sharing is on by default; when location permission
and a fix no older than five minutes are available, the signed SOS includes latitude and
longitude. If unavailable or disabled, the SOS is sent without location.
New installations start with mesh sharing on and cloud consent off; existing
drafts keep their choices. With mesh sharing off, **Save on this phone** saves
locally. Public reports stay queued without peers. Joining alone never sends a
new SOS, though previously queued public reports resume automatically.

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
Relay pauses when the app backgrounds and resumes when returning to the app;
**Disconnect from GHOST** stops it until you join again.
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

The server public signing key can be pinned with
`-PghostCloudPublicKey=<base64url-SPKI>`. Every phone carrying or receiving cloud
acknowledgements needs this same trusted key. Empty configuration rejects cloud results.
Shared wire definitions live in `contracts/ghost.proto`; signature vectors use
public test-only keys, never deployment credentials.

For a cloud gateway, build with the deployed server's public signing key using
`-PghostCloudPublicKey=<base64url-SPKI>`. In **Connections → Configure gateway**, enter
the server address and a gateway bearer token supplied by the server operator.
The token is stored encrypted with Android Keystore; it is never baked into the
APK. Normal mesh phones need no gateway setup.

Join the mesh and enable **Allow online help coordination** when sending an SOS.
While open, the configured gateway uploads eligible reports, including received
reports, and retries temporary failures. **Received by server** appears only after
a matching pinned signature is saved in Room. An offline sender sees it after
the signed acknowledgement reaches their phone through the mesh. Use HTTPS;
debug builds also allow HTTP for LAN/emulator testing (`10.0.2.2` reaches the
emulator host). Upload and return paths have host HTTP/storage/exchange tests;
deployed phone-to-cloud-to-mesh testing is still needed.

## Helpers and supplies

From Home, tap **Offer help**. Choose **Volunteer** (help type, optional name and
area, available/unavailable) or **Supplies** (resource type, optional quantity,
unit and area). Save before sharing; availability expires after six hours.
**Share with nearby phones** and **Allow online help coordination** are separate
choices. With nearby sharing off, the publication stays on the phone even if
online consent is checked. There is no automatic assignment or reservation.
For volunteers, turning sharing off also queues an unavailable report for the
previous audience, without a name or area. New private details stay local. Turning
only online sharing off can leave your new availability shared nearby while the
server receives the withdrawal. The withdrawal keeps the previous expiry and waits
for delivery; a separate verified receipt shows when the server has received it.

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
offline/older copies may remain stale until updated or expired. Matching/approval
is next work.

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

Open `http://127.0.0.1:3000/dashboard/` and enter a **viewer or operator** token
supplied by the server administrator. It lists uploaded SOS reports and plots
reports with shared GPS coordinates on the built-in server map. Select a marker
to open its request details; reports without coordinates remain in the list but
are not plotted. Leaflet provides pan/zoom and clickable markers over OpenStreetMap
tiles. The basemap needs internet; tile requests reveal the viewed map area to
OpenStreetMap but do not send SOS text or access tokens. Refresh checks for new arrivals;
older reports load in pages. Expiry does not mean help was provided. Tokens stay
in tab memory; reload/sign-out clears access. Use HTTPS in deployment.

For UI development, run `npm --prefix dashboard run dev` alongside the server;
open `http://127.0.0.1:5173/dashboard/`. Vite proxies `/v1` to port 3000.
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
withdrawals invalidate older proposals. Suggestions require human review; approval,
owner acceptance and confirmed assistance delivery are the next steps.
