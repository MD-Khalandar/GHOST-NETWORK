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

1. Keep Bluetooth/Wi-Fi on and both apps open. In Network, tap **Start nearby** and allow the requested permissions.
2. Tap **Connect** on one phone, compare the code, and confirm it on both.
3. On Home, create an SOS, enable public sharing, review, then **Save & send SOS**.
4. Check Requests on the other phone. The sender shows receipt only after the receiver saves the report and returns a valid signature.

With sharing off, **Save SOS** keeps the report on this phone. With no connected
peer, public reports stay queued across restarts; reconnect to send them.

To try a relay, send A's SOS to B, disconnect A, then connect B to C. C should
receive A's unchanged report. Forwarding stops after four transfers or expiry.
Receipts confirm saving by the immediate neighbor. Each phone remembers which
neighbors already hold a report across restarts and reconnects; unacknowledged
sends remain eligible for retry. Duplicates are saved only once. Existing local
data upgrades in place. Physical three-phone testing is still pending.

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
