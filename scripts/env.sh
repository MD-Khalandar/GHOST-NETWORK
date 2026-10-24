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
export JAVA_HOME="${GHOST_JAVA_HOME:-$GHOST_ROOT/.local/toolchains/jdk-21/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$GHOST_ROOT/.local/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$GHOST_ROOT/.local/gradle-user-home}"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$GHOST_ROOT/.local/android-user-home}"
export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

