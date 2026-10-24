# GHOST

Android/Kotlin/Jetpack Compose mesh communication app. Build SOS/help first with
one cloud coordinator, reached by offline phones through mesh gateways, with a
shared request/helper/supply database and a web dashboard. Messaging,
local LLM helpers and other in-app internet services follow later. The current app
has preview screens and an identity/trust foundation; SOS sending is still pending.
See [the overview](README_START_HERE.md), [10 checkpoints](docs/CHECKPOINTS.md)
and [team prompts](docs/BUILD_PHASES.md).

Develop the same project on **Windows, macOS, or Linux**. It produces an Android
app, which runs on an Android phone or emulator. No separate Kotlin or Gradle
installation is needed: the checked-in Gradle wrapper downloads the pinned build
tools and dependencies. All platforms need internet for their first setup/build.

## Windows (x64, PowerShell 5.1 or newer)

Install [Git for Windows](https://git-scm.com/downloads/win), then open PowerShell:

```powershell
git clone https://github.com/MD-Khalandar/GHOST-NETWORK.git
cd GHOST-NETWORK
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
.\scripts\setup-windows.ps1
. .\scripts\env.ps1
.\gradlew.bat assembleDebug testDebugUnitTest
```

The installer downloads a checksum-verified Temurin JDK 21 and Android
command-line tools, then installs platform-tools, Android API 36, and Build Tools
36.0.0. Review/accept SDK licenses when prompted. Tools and caches live in ignored
`.local/`; no administrator access or permanent environment changes are needed.
The execution-policy setting lasts only for this PowerShell session; managed
computers may enforce a policy that takes precedence.

To download the optional local model for later experiments, use
`.\scripts\setup-windows.ps1 -WithModel`. The model is unnecessary for building
the current scaffold and is never included in Git or the APK.

In each new PowerShell terminal, activate the environment before building:

```powershell
. .\scripts\env.ps1
.\gradlew.bat assembleDebug testDebugUnitTest
```

Optional IDE: install [Android Studio](https://developer.android.com/studio),
open this repository, set its Android SDK location to this project's
`.local\android-sdk` (or your explicit `ANDROID_HOME`), and set **Gradle JDK** to
`.local\toolchains\jdk-21`. Create/start an emulator in **Device Manager**, or
connect an Android phone with USB debugging enabled and authorize the computer.
Windows phones may need their manufacturer's USB driver.

With one target connected:

```powershell
adb devices
.\gradlew.bat installDebug
adb shell am start -n org.ghost/.MainActivity
```

The automated Windows installer targets Intel/AMD x64 PCs. Windows ARM64 is not
verified or covered by this installer.

## Apple Silicon macOS

With Git installed:

```bash
git clone https://github.com/MD-Khalandar/GHOST-NETWORK.git
cd GHOST-NETWORK
./scripts/setup-macos.sh --with-studio
source scripts/env.sh
./gradlew assembleDebug testDebugUnitTest
./scripts/studio.sh
```

Add `--with-emulator` to setup to provision the optional `GHOST_API_35` emulator.
Start it with `emulator -avd GHOST_API_35`, or connect a phone, then run
`./gradlew installDebug`. Source `scripts/env.sh` in each new terminal.

## Linux and Intel macOS (existing toolchains)

Install **JDK 21** for your computer and
[Android Studio](https://developer.android.com/studio). In Studio's SDK Manager,
install **Android SDK Platform 36**, **Build Tools 36.0.0**, **Platform-Tools**,
and **Android SDK Command-line Tools (latest)**. Select JDK 21 as Studio's
Gradle JDK. Use Device Manager to create an emulator, or connect an Android phone.
Linux may require distribution-specific USB/udev setup for a physical device.

```bash
git clone https://github.com/MD-Khalandar/GHOST-NETWORK.git
cd GHOST-NETWORK
# Set JAVA_HOME to your installed JDK 21 directory before activating.
# Set ANDROID_HOME if your SDK is outside the standard Android Studio location.
source scripts/env.sh
./gradlew assembleDebug testDebugUnitTest
./gradlew installDebug
adb shell am start -n org.ghost/.MainActivity
```

The shell environment preserves an existing `JAVA_HOME` when there is no local
JDK, and discovers Studio's standard SDK at `~/Android/Sdk` on Linux or
`~/Library/Android/sdk` on macOS. `GHOST_JAVA_HOME` explicitly overrides JDK
selection on every platform. The full automatic Mac installer is Apple Silicon
only; Linux and Intel Mac use the manual prerequisites above.

## Outputs and verification

- APK: `app/build/outputs/apk/debug/app-debug.apk`.
- Unit-test report: `app/build/reports/tests/testDebugUnitTest/index.html`.
- Minimum Android API: 24; handset model/radio compatibility requires real-device testing.
- Mac foundation checks and Windows build/host trust tests are recorded. The full
  Windows installer, Linux setup and physical-device behavior still need their own
  verification; host tests do not prove radio relay or cloud coordination. The
  separate [server](server/README.md) implements trust guards, with persistence pending.

Keep `local.properties`, SDKs, caches, signing credentials, and model weights out
of Git. See [environment details](docs/ENVIRONMENT_SETUP.md) and the
[acceptance matrix](docs/TEST_MATRIX.md).

Tool references: [Android SDK tools](https://developer.android.com/tools/sdkmanager),
[SDK environment variables](https://developer.android.com/tools/variables), and
[Temurin distributions](https://adoptium.net/temurin/releases/?version=21).
