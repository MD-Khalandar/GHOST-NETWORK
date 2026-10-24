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
`installDebug` and launch GHOST. The current Send SOS action opens a preview;
real sending is still being built.

The server public signing key can be pinned with
`-PghostCloudPublicKey=<base64url-SPKI>`. Empty configuration rejects cloud results.
Shared wire definitions live in `contracts/ghost.proto`; signature vectors use
public test-only keys, never deployment credentials.

## Server

See [server/README.md](server/README.md). Temporary tools/caches belong in ignored
`.tmp/` or `.local/`; credentials and build outputs stay out of Git.
