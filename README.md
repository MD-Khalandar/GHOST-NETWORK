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
locally. Nearby runs while the app is open and can relay public SOS reports;
cloud uploads are not implemented yet.

For two phones with Google Play services:

1. Keep Bluetooth/Wi-Fi on and both apps open. Tap **Connect to mesh** on each and allow Android's required permissions. Discovery, connections and reconnection are automatic; no phone selection or pairing approval.
2. Tap **Send SOS**, describe the help needed, then **Send SOS**. Category, area and a +/− people counter are under **Optional details**. There is no review screen.
3. Check Requests on the other phone. The sender shows receipt only after the receiver saves the report and returns a valid signature.

The form shows the sharing audience before sending. **Sharing settings** keeps
mesh sharing and cloud consent separate and remembers them for the next SOS.
New installations start with mesh sharing on and cloud consent off; existing
drafts keep their choices. With mesh sharing off, **Save on this phone** saves
locally. Public reports stay queued without peers. Joining alone never sends a
new SOS, though previously queued public reports resume automatically.

To try a relay, send A's SOS to B, leave the mesh on A, then let B meet C. C should
receive A's unchanged report. Forwarding stops after four transfers or expiry.
Receipts confirm saving by the immediate neighbor. Each phone remembers which
neighbors already hold a report across restarts and reconnects; unacknowledged
sends remain eligible for retry. Duplicates are saved only once. Existing local
data upgrades in place. Physical three-phone testing is still pending.

The mesh is open to nearby GHOST phones; connections have no manual code check.
Signing verifies report integrity and key possession, not a person's identity.
Relay pauses when the app backgrounds and resumes when returning to the app;
**Disconnect from mesh** stops it until you join again.
Network's **Your nearby mesh** graph updates as direct neighbors connect and leave.
Solid links are ready to relay; dashed links are still checking identity. It shows
only this phone's direct connections, not the entire mesh or an SOS delivery path.

Each phone has independent bounded queues. A report waits for its neighbor's
signed receipt before the next report goes out on that link. Missing receipts
trigger up to three attempts per connection; **Retry queued sends** resumes them
without forgetting saved receipts. Requests shows relay acknowledgements and
the remaining transfer budget. Expired reports are never queued for another send.
This relay handles public SOS reports and neighbor receipts; cloud uploads and
cloud responses returning through the mesh are separate upcoming work.

The server public signing key can be pinned with
`-PghostCloudPublicKey=<base64url-SPKI>`. Empty configuration rejects cloud results.
Shared wire definitions live in `contracts/ghost.proto`; signature vectors use
public test-only keys, never deployment credentials.

## Server

See [server/README.md](server/README.md). Temporary tools/caches belong in ignored
`.tmp/` or `.local/`; credentials and build outputs stay out of Git.
