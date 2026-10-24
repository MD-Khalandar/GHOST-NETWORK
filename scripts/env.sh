# Source this file from bash or zsh: source scripts/env.sh
if [ -n "${ZSH_VERSION:-}" ]; then
    GHOST_ROOT="$(cd -- "$(dirname -- "${(%):-%x}")/.." && pwd)"
elif [ -n "${BASH_VERSION:-}" ]; then
    GHOST_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
else
    printf '%s\n' 'Please source scripts/env.sh using bash or zsh.' >&2
    return 1
fi
export GHOST_ROOT
if [ -n "${GHOST_JAVA_HOME:-}" ]; then
    export JAVA_HOME="$GHOST_JAVA_HOME"
elif [ -x "$GHOST_ROOT/.local/toolchains/jdk-21/Contents/Home/bin/java" ]; then
    export JAVA_HOME="$GHOST_ROOT/.local/toolchains/jdk-21/Contents/Home"
elif [ -z "${JAVA_HOME:-}" ] && [ "$(uname -s)" = Darwin ]; then
    export JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
fi
# Retain the default path used by the Apple Silicon bootstrap before installation.
export JAVA_HOME="${JAVA_HOME:-$GHOST_ROOT/.local/toolchains/jdk-21/Contents/Home}"
if [ -z "${ANDROID_HOME:-}" ]; then
    if [ -d "$GHOST_ROOT/.local/android-sdk" ]; then
        export ANDROID_HOME="$GHOST_ROOT/.local/android-sdk"
    elif [ "$(uname -s)" = Darwin ] && [ -d "$HOME/Library/Android/sdk" ]; then
        export ANDROID_HOME="$HOME/Library/Android/sdk"
    elif [ -d "$HOME/Android/Sdk" ]; then
        export ANDROID_HOME="$HOME/Android/Sdk"
    else
        export ANDROID_HOME="$GHOST_ROOT/.local/android-sdk"
    fi
fi
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$GHOST_ROOT/.local/gradle-user-home}"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$GHOST_ROOT/.local/android-user-home}"
export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

