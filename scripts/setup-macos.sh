#!/usr/bin/env bash
# Install an isolated Apple Silicon toolchain. Add --with-emulator and/or --with-studio.
set -euo pipefail
# This installer provisions its own JDK even when another JAVA_HOME is set.
export JAVA_HOME="${GHOST_JAVA_HOME:-$(cd "$(dirname "$0")/.." && pwd)/.local/toolchains/jdk-21/Contents/Home}"
source "$(dirname "$0")/env.sh"
cd "$GHOST_ROOT"
if [[ "$(uname -s)" != Darwin || "$(uname -m)" != arm64 ]]; then
    printf '%s\n' 'This bootstrap is pinned for Apple Silicon macOS.' >&2
    exit 1
fi

with_emulator=false
with_studio=false
for option in "$@"; do
    case "$option" in
        --with-emulator) with_emulator=true ;;
        --with-studio) with_studio=true ;;
        *) printf 'Unknown option: %s\n' "$option" >&2; exit 1 ;;
    esac
done

mkdir -p .local/downloads .local/toolchains .local/apps \
    "$ANDROID_HOME" "$ANDROID_USER_HOME" "$ANDROID_AVD_HOME" "$GRADLE_USER_HOME" .gradle

verify() {
    [[ "$(shasum -a "$2" "$1" | awk '{print $1}')" == "$3" ]]
}

download() {
    local url="$1" destination="$2" algorithm="$3" checksum="$4"
    if [[ -f "$destination" ]] && verify "$destination" "$algorithm" "$checksum"; then
        printf 'Verified cached download: %s\n' "$destination"
        return
    fi
    if [[ -f "$destination.part" ]] && verify "$destination.part" "$algorithm" "$checksum"; then
        mv "$destination.part" "$destination"
        printf 'Verified completed download: %s\n' "$destination"
        return
    fi
    curl --http1.1 --fail --location --show-error --retry 5 --retry-all-errors --continue-at - \
        --connect-timeout 20 --speed-limit 1024 --speed-time 90 \
        "$url" -o "$destination.part"
    if ! verify "$destination.part" "$algorithm" "$checksum"; then
        printf 'Checksum mismatch: %s.part\n' "$destination" >&2
        exit 1
    fi
    mv "$destination.part" "$destination"
}

download \
    'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.13_9.tar.gz' \
    .local/downloads/jdk21.tar.gz 256 bf036b35b071bfb2f54f2c0af8ee8d88afa95ee6eb3dce02c0f94cfc2524d04a
if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
    mkdir -p .local/toolchains/jdk-21
    tar -xzf .local/downloads/jdk21.tar.gz -C .local/toolchains/jdk-21 --strip-components=1
fi
java -version

download https://dl.google.com/android/repository/commandlinetools-mac_arm64-16111833_latest.zip \
    .local/downloads/commandlinetools.zip 1 ad03dc49bfacfd52c110b14104ea548b8a07e830
if [[ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]]; then
    mkdir -p "$ANDROID_HOME/cmdline-tools"
    unzip -q .local/downloads/commandlinetools.zip -d "$ANDROID_HOME/cmdline-tools"
    mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
fi

download https://services.gradle.org/distributions/gradle-9.4.1-bin.zip \
    .local/downloads/gradle-9.4.1-bin.zip 256 2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb
if [[ ! -x .local/toolchains/gradle-9.4.1/bin/gradle ]]; then
    unzip -q .local/downloads/gradle-9.4.1-bin.zip -d .local/toolchains
fi

printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
printf 'java.home=%s\n' "$JAVA_HOME" > .gradle/config.properties
if [[ ! -f .idea/gradle.xml ]]; then
    mkdir -p .idea
    cat > .idea/gradle.xml <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<project version="4">
  <component name="GradleSettings">
    <option name="linkedExternalProjectsSettings">
      <GradleProjectSettings>
        <option name="externalProjectPath" value="$PROJECT_DIR$" />
        <option name="gradleJvm" value="#GRADLE_LOCAL_JAVA_HOME" />
      </GradleProjectSettings>
    </option>
  </component>
</project>
XML
fi

# Command-line tools 23 uses Android CLI; sdkmanager --licenses is now a no-op.
android --no-metrics --sdk="$ANDROID_HOME" sdk install \
    platform-tools platforms/android-36 build-tools/36.0.0

if "$with_emulator"; then
    android --no-metrics --sdk="$ANDROID_HOME" sdk install \
        emulator system-images/android-35/google_apis/arm64-v8a
    if [[ ! -f "$ANDROID_AVD_HOME/GHOST_API_35.ini" ]]; then
        printf 'no\n' | avdmanager create avd --name GHOST_API_35 \
            --package 'system-images;android-35;google_apis;arm64-v8a' --device pixel_6
    fi
fi

if "$with_studio"; then
    download https://edgedl.me.gvt1.com/android/studio/install/2026.2.1.8/android-studio-rabbit1-mac_arm.dmg \
        .local/downloads/android-studio.dmg 256 16d4a0f8a52413b51869fc18d5bdcdf9cecd38615cd7bb7cf14d51b99d2fef54
    if [[ ! -d '.local/apps/Android Studio.app' ]]; then
        studio_mount="$(mktemp -d /private/tmp/ghost-studio.XXXXXX)"
        trap 'hdiutil detach "$studio_mount" >/dev/null 2>&1 || true' EXIT
        hdiutil attach .local/downloads/android-studio.dmg -nobrowse -readonly -mountpoint "$studio_mount"
        ditto "$studio_mount/Android Studio.app" '.local/apps/Android Studio.app'
        hdiutil detach "$studio_mount"
        trap - EXIT
    fi
fi

printf '%s\n' 'Toolchain installed. Source scripts/env.sh, then run ./gradlew assembleDebug testDebugUnitTest.'

