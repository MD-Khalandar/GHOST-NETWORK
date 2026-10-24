# Dot-source in each new PowerShell session: . .\scripts\env.ps1
$env:GHOST_ROOT = Split-Path $PSScriptRoot -Parent
if ($env:GHOST_JAVA_HOME) {
    $env:JAVA_HOME = $env:GHOST_JAVA_HOME
} elseif (Test-Path "$env:GHOST_ROOT\.local\toolchains\jdk-21\bin\java.exe") {
    $env:JAVA_HOME = "$env:GHOST_ROOT\.local\toolchains\jdk-21"
}
if (-not $env:ANDROID_HOME) {
    if (Test-Path "$env:GHOST_ROOT\.local\android-sdk") {
        $env:ANDROID_HOME = "$env:GHOST_ROOT\.local\android-sdk"
    } elseif (Test-Path "$env:LOCALAPPDATA\Android\Sdk") {
        $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
    } else {
        $env:ANDROID_HOME = "$env:GHOST_ROOT\.local\android-sdk"
    }
}
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
if (-not $env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME = "$env:GHOST_ROOT\.local\gradle-user-home" }
if (-not $env:ANDROID_USER_HOME) { $env:ANDROID_USER_HOME = "$env:GHOST_ROOT\.local\android-user-home" }
$env:ANDROID_AVD_HOME = "$env:ANDROID_USER_HOME\avd"
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\emulator;$env:PATH"
