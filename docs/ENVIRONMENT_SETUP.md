# Local development environment

For current Windows, macOS, and Linux onboarding commands, start with
[README.md](../README.md). The Apple Silicon bootstrap and observations below
describe the original verified host. `scripts/setup-windows.ps1` adds a
project-local x64 Windows installer; it has not been executed on Windows here.
Linux and Intel Mac use installed JDK 21/Android Studio toolchains. No Windows,
Linux, or hardware acceptance gate is implied by the Mac verification record.

The current phase builds SOS in one Kotlin/Compose APK plus a separate cloud
coordinator service. Room holds local phone state; the server owns accepted shared
coordination state. Nearby provides neighbor links and MeshEngine handles relay.
The cloud service/provider/authentication are selected at checkpoint 1; this
Android bootstrap does not provision them. The planned `dashboard/` frontend uses
the same server APIs/database for incoming SOS, helpers, supplies and approvals.
Its toolchain/run commands will be documented when scaffolded; this is not part of
the existing Android installation. Local LLM features are deferred.
See `ARCHITECTURE.md` and the current 10-checkpoint plan.

The original folder contained only eight Markdown documents, without source code,
Gradle files, a Git repository, or the `.agents/skills/` directory mentioned by the
starter README. The bootstrap added build infrastructure and a minimal launcher,
not product features. Packet/event contracts remain documentation until their
implementation checkpoint; this historical setup record is not feature evidence.

## Installed toolchain

The bootstrap targets **Apple Silicon macOS**. Downloads, tools, caches, and model
weights live in the ignored `.local/` directory. Shell startup files and global
Java/Android installations are not modified.

| Component | Pin / location |
| --- | --- |
| Java | Eclipse Temurin JDK 21.0.13+9, `.local/toolchains/jdk-21/Contents/Home` |
| Gradle | 9.4.1, checksum-pinned project wrapper |
| Android Gradle Plugin | 9.2.1, with built-in Kotlin |
| Kotlin / Compose compiler | 2.4.21, explicitly aligned with LiteRT-LM metadata |
| KSP | 2.3.12 |
| SDK command-line tools | 23.0, includes the Android CLI launcher |
| SDK platform / build tools | Android API 36 / 36.0.0 |
| Platform tools | 37.0.1, including ADB |
| Android Studio | Rabbit 1 (2026.2.1.8), `.local/apps/Android Studio.app` |
| Model | `Qwen3-0.6B.litertlm`, `.local/models/` |

`gradle/libs.versions.toml` pins Compose Material 3, lifecycle, Room + KSP,
coroutines/Flow, JSON serialization, Nearby Connections, LiteRT-LM, protobuf-lite,
and host test dependencies. They are resolved through Google Maven, Maven Central,
and the Gradle plugin portal. No API key, cloud inference service, Node.js,
Homebrew, NDK, CMake, or Python ML environment is required to build this scaffold.
Cloud coordination tests require a configured authenticated service; provider
secrets stay server-side and are not part of the Android setup.
LiteRT-LM supplies prebuilt native libraries. Choose a signature library and
Keystore scheme when implementing the security gate against actual handsets.

JDK 21 is required for the selected LiteRT-LM release's host-side classes (Java
class-file version 65). AGP's JDK 17 minimum alone is insufficient for those unit
tests. Android app source still targets JVM 17 bytecode; D8 processes the library
classes into DEX for Android.

Only the protobuf runtime is configured at this stage. Add schema generation and
the matching `protoc` dependency when the first agreed `.proto` files and fixture
vectors are introduced; this setup does not invent or freeze the wire schema.

The application ID is `org.ghost`, `compileSdk` and `targetSdk` are 36, and `minSdk`
is 24 (Android 7.0). This SDK minimum is not proof that an older phone can run the
model. The app currently only shows a development setup screen. It does not
request radio/location permissions, initialize a model, or send network traffic.

## Use the environment

From the repository root, in bash or zsh:

```sh
source scripts/env.sh
./scripts/doctor.sh
./gradlew assembleDebug testDebugUnitTest
```

`source` affects the current terminal. Run it in each new terminal before Gradle,
ADB, or Android CLI commands. The SDK path is recorded in ignored
`local.properties`. Ignored `.gradle/config.properties` and `.idea/gradle.xml`
select the project JDK through Android Studio's `GRADLE_LOCAL_JAVA_HOME` setting.
To deliberately use another compatible JDK, set `GHOST_JAVA_HOME` before sourcing
the environment file.
The doctor script reads installed SDK metadata locally and does not need to fetch
the SDK package catalog.

To open the project with the locally installed Android Studio:

```sh
./scripts/studio.sh
```

If Studio offers a setup wizard, choose the existing SDK at
`<project>/.local/android-sdk` and the JDK at
`<project>/.local/toolchains/jdk-21/Contents/Home`. Use the Gradle wrapper and, if
needed, set Gradle user home to `<project>/.local/gradle-user-home` to reuse the
downloaded dependencies. The CLI build is sufficient without completing Studio's
optional account/Gemini setup.

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. The host
unit-test report is at `app/build/reports/tests/testDebugUnitTest/index.html`.

## Reinstall or provision another Apple Silicon checkout

```sh
./scripts/setup-macos.sh --with-studio
source scripts/env.sh
./gradlew assembleDebug testDebugUnitTest
```

The script verifies JDK, command-line tools, Gradle, Studio, and model downloads
against their published checksums. SDK packages are installed by Google's CLI.
Internet access is required for the initial install and dependency resolution.
Installation into `.local/` avoids administrator access. Git is initialized
locally on `main`; no remote or commit is created by setup.

An emulator is optional for future UI work and is **not required or installed by
the default bootstrap**. To add an ARM64 API 35 Google APIs emulator later:

```sh
./scripts/setup-macos.sh --with-emulator
source scripts/env.sh
emulator -avd GHOST_API_35
```

Emulator checks cannot satisfy physical mesh or actual cloud-provider evidence.
The original `$ghost-*` review skills named in `AGENTS.md` were
absent from the supplied folder; recover their original `.agents/skills/` files
before invoking those review workflows. The Google Android skills are optional
tooling; see the official Android skills repository linked below.

## Offline model provisioning (deferred)

Retained for later local-helper experiments, outside the SOS checkpoints. Existing
dependencies/downloads are not a requirement to run the cloud coordinator.

The downloaded candidate is the CPU/GPU `Qwen3-0.6B.litertlm` from
`litert-community/Qwen3-0.6B`, not an NPU-specific or web build.

```text
Expected bytes: 613722264
Repository revision: e6687177e00897731e7716267eebd4eba0dde7c2
SHA-256: c5f39b4191926786c3e6668b62af4d6eb15248dfc7a5348252a9b406f33f2108
```

Verify it at any time with:

```sh
shasum -a 256 .local/models/Qwen3-0.6B.litertlm
```

The model is excluded from Git and the APK. A downloaded and hash-verified file
does not establish phone compatibility, inference, JSON compliance, or tool
selection. Runtime integration and a user-visible missing/loading/failed model
state still need implementation.

For a later local-helper experiment, after selecting a capable phone, enabling USB debugging and installing the
debug app, these are the manual provisioning commands. If multiple phones are
attached, replace `PHONE_C_SERIAL` with that test phone's serial from `adb devices
-l`. These commands have not been run on hardware during setup:

```sh
adb -s PHONE_C_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s PHONE_C_SERIAL push .local/models/Qwen3-0.6B.litertlm /data/local/tmp/ghost-model.litertlm
adb -s PHONE_C_SERIAL shell run-as org.ghost mkdir -p files
adb -s PHONE_C_SERIAL shell run-as org.ghost cp /data/local/tmp/ghost-model.litertlm files/Qwen3-0.6B.litertlm
```

The future runtime must load this file from `filesDir` on a dedicated background
owner. First inference must be tested with internet disabled, with the exact
handset, model hash, backend, latency, and validated output recorded.

## Verification and remaining gates

The setup's `DependencyCompatibilityTest` checks host-side linkage of the
LiteRT-LM configuration API, protobuf-lite, JSON, and coroutine/Flow test runtime.
It does not initialize native inference or test transport, Room persistence,
authorization, or GHOST packet encoding. Build/test observations are recorded
below.

Observed on **23 October 2026**, on this Apple Silicon Mac:

- `./scripts/setup-macos.sh --with-studio`: exit 0; all pinned downloads verified,
  JDK 21 installed, SDK and Studio configured.
- `./gradlew assembleDebug testDebugUnitTest --console=plain`: exit 0 using JDK 21.
  `BUILD SUCCESSFUL in 1m 3s`; 44 actionable tasks (20 executed, 24 up-to-date).
- JUnit XML: **3 tests, 0 failures, 0 errors, 0 skipped**.
- `./scripts/doctor.sh`: exit 0; reports JDK 21.0.13, Android CLI 1.0.16625370,
  command-line tools 23.0, platform-tools 37.0.1, platform API 36, build tools
  36.0.0, matching model checksum, and no attached devices.
- `codesign --verify --deep --strict` on the installed Android Studio: exit 0,
  valid on disk and satisfies its designated requirement.
- APK generated: **62,867,834 bytes**, with minimum SDK 24 and target SDK 36.
  ZIP inspection confirms no `.litertlm` model files are bundled.
- The first host test run on JDK 17 exposed LiteRT-LM's Java 21 requirement. The
  environment and bootstrap were updated to JDK 21 before the successful run.

The successful Gradle console output is saved locally in
`.local/verification/build-and-tests.log`. The JUnit XML and HTML report are in
the standard `app/build/test-results/` and `app/build/reports/tests/` directories.

No phones were attached when ADB was checked. All physical acceptance tests in
`docs/TEST_MATRIX.md` remain open, including Play Services availability, Nearby
permissions and matching-code pairing, true A → B → C relay, restart recovery,
cloud ingestion/coordination and signed recipient receipts. No cloud service was
provisioned by this bootstrap. Saved-contact messaging, local inference and
Meshtastic radios are deferred; none has acceptance evidence from setup.

## Files added or updated

| Files | Purpose |
| --- | --- |
| `.gitignore` | Exclude tools, models, caches, local settings, builds, and keys |
| `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties` | Single `:app` build and repository configuration |
| `gradle/libs.versions.toml` | Pinned plugins and app/test dependencies |
| `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties` | Standard Gradle wrapper with distribution checksum |
| `app/build.gradle.kts` | Android, Compose, KSP, Room, Nearby, LiteRT-LM, and test configuration |
| `app/src/main/AndroidManifest.xml` | Launcher and optional GPU native-library declarations |
| `app/src/main/java/org/ghost/app/MainActivity.kt` | Minimal stateless development screen and preview |
| `app/src/main/res/values/strings.xml`, `app/src/main/res/values/themes.xml` | Bootstrap labels and Android theme |
| `app/src/test/java/org/ghost/setup/DependencyCompatibilityTest.kt` | Three host dependency compatibility checks |
| `scripts/setup-macos.sh`, `scripts/env.sh`, `scripts/doctor.sh`, `scripts/studio.sh` | Install, activate, verify, and open the local environment |
| `README_START_HERE.md`, `docs/ENVIRONMENT_SETUP.md` | Setup entry point, project explanation, and verification record |

Generated machine-specific files (`local.properties`, `.gradle/config.properties`,
`.idea/gradle.xml`) and the `.local/` installation are ignored. No existing
architecture, protocol, agent contract, UX specification, or acceptance gate was
changed.

## Official sources checked

- [AGP 9.2 compatibility](https://developer.android.com/build/releases/agp-9-2-0-release-notes)
- [Built-in Kotlin version alignment](https://developer.android.com/build/releases/agp-9-0-0-release-notes#runtime-dependency-on-kotlin-gradle-plugin)
- [Android Studio and command-line tools](https://developer.android.com/studio)
- [Android CLI](https://developer.android.com/tools/agents/android-cli)
- [LiteRT-LM Android](https://developers.google.com/edge/litert-lm/android)
- [Nearby Connections Android setup](https://developers.google.com/nearby/connections/android/get-started)
- [Room dependencies](https://developer.android.com/jetpack/androidx/releases/room)
- [Qwen3 LiteRT-LM model](https://huggingface.co/litert-community/Qwen3-0.6B)
- [Official Android skills](https://github.com/android/skills)

