#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/env.sh"
printf 'Project: %s\n' "$GHOST_ROOT"
java -version
android --no-metrics --version
adb version
# Read installed metadata directly so this check also works without internet.
for package in cmdline-tools/latest platform-tools platforms/android-36 build-tools/36.0.0; do
    properties="$ANDROID_HOME/$package/source.properties"
    if [[ ! -f "$properties" ]]; then
        printf 'Missing SDK package: %s\n' "$package" >&2
        exit 1
    fi
    printf '\n%s\n' "$package"
    sed -n '/^Pkg.Revision/p; /^AndroidVersion.ApiLevel/p' "$properties"
done
adb devices -l
if [ -f "$GHOST_ROOT/.local/models/Qwen3-0.6B.litertlm" ]; then
    shasum -a 256 "$GHOST_ROOT/.local/models/Qwen3-0.6B.litertlm"
else
    printf '%s\n' 'Coordinator model is not downloaded.'
fi
printf '%s\n' 'Physical relay, runtime permissions, and offline model inference require real phones.'

