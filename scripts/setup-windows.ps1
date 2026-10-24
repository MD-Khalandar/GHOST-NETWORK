# Requires Windows x64 and PowerShell 5.1+. Installs tools locally, without admin.
# Android Studio is optional; use its SDK/Device Manager to add an emulator.
[CmdletBinding()]
param([switch]$WithModel)
$ErrorActionPreference = 'Stop'
if ($env:OS -ne 'Windows_NT' -or [Environment]::Is64BitOperatingSystem -eq $false -or
    $env:PROCESSOR_ARCHITECTURE -eq 'ARM64' -or $env:PROCESSOR_ARCHITEW6432 -eq 'ARM64') {
    throw 'This installer supports Windows x64. See README.md for other hosts.'
}
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$projectRoot = Split-Path $PSScriptRoot -Parent
$downloadRoot = Join-Path $projectRoot '.local\downloads'
New-Item -ItemType Directory -Force $downloadRoot | Out-Null

function Get-VerifiedDownload {
    param([string]$Url, [string]$Destination, [string]$Sha256)
    if ((Test-Path $Destination) -and
        (Get-FileHash $Destination -Algorithm SHA256).Hash -eq $Sha256) { return }
    $partial = "$Destination.part"
    Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $partial
    if ((Get-FileHash $partial -Algorithm SHA256).Hash -ne $Sha256) {
        throw "Checksum mismatch: $partial. No archive was extracted."
    }
    Move-Item -Force $partial $Destination
}

# Keep the Java major version at 21; verify the archive against Adoptium metadata.
$jdkRoot = Join-Path $projectRoot '.local\toolchains\jdk-21'
if (-not (Test-Path "$jdkRoot\bin\java.exe") -and -not $env:GHOST_JAVA_HOME) {
    $assets = @(Invoke-RestMethod -Uri 'https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse')
    if ($assets.Count -eq 0) { throw 'No Windows x64 JDK 21 archive returned by Adoptium.' }
    $package = $assets[0].binary.package
    $archive = Join-Path $downloadRoot 'jdk21-windows.zip'
    Get-VerifiedDownload $package.link $archive $package.checksum
    $extractRoot = Join-Path $downloadRoot 'jdk21-extracted'
    if (Test-Path $extractRoot) { Remove-Item -Recurse -Force $extractRoot }
    Expand-Archive $archive $extractRoot
    $jdkFolders = @(Get-ChildItem $extractRoot -Directory)
    if ($jdkFolders.Count -ne 1 -or -not (Test-Path "$($jdkFolders[0].FullName)\bin\java.exe")) {
        throw 'Unexpected JDK archive structure.'
    }
    New-Item -ItemType Directory -Force (Split-Path $jdkRoot -Parent) | Out-Null
    if (Test-Path $jdkRoot) { throw "Incomplete JDK at $jdkRoot; move it aside and rerun." }
    Move-Item $jdkFolders[0].FullName $jdkRoot
}
. "$PSScriptRoot\env.ps1"
if (-not (Test-Path "$env:JAVA_HOME\bin\java.exe")) { throw 'Set GHOST_JAVA_HOME to a JDK 21 directory.' }
$javaVersion = (& "$env:JAVA_HOME\bin\java.exe" --version | Out-String)
if ($LASTEXITCODE -ne 0 -or $javaVersion -notmatch '(?m)^(openjdk|java) 21\.') { throw "JDK 21 required. Found: $javaVersion" }
Write-Host $javaVersion

$toolsRoot = Join-Path $env:ANDROID_HOME 'cmdline-tools\latest'
if (-not (Test-Path "$toolsRoot\bin\sdkmanager.bat")) {
    # Fixed archive and SHA-256 from developer.android.com/studio.
    $archive = Join-Path $downloadRoot 'commandlinetools-windows.zip'
    Get-VerifiedDownload 'https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip' $archive '90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a'
    $extractRoot = Join-Path $downloadRoot 'android-tools-extracted'
    if (Test-Path $extractRoot) { Remove-Item -Recurse -Force $extractRoot }
    Expand-Archive $archive $extractRoot
    New-Item -ItemType Directory -Force (Split-Path $toolsRoot -Parent) | Out-Null
    if (Test-Path $toolsRoot) { throw "Incomplete SDK tools at $toolsRoot; move them aside and rerun." }
    Move-Item "$extractRoot\cmdline-tools" $toolsRoot
}

# Java .properties files need forward slashes and escaped non-ASCII characters.
function ConvertTo-PropertyPath([string]$Value) {
    $result = ''
    foreach ($character in $Value.Replace('\', '/').ToCharArray()) {
        if ([int]$character -gt 127) { $result += '\u{0:x4}' -f [int]$character }
        else { $result += $character }
    }
    return $result
}
Set-Content -Encoding ASCII (Join-Path $projectRoot 'local.properties') "sdk.dir=$(ConvertTo-PropertyPath $env:ANDROID_HOME)"
New-Item -ItemType Directory -Force "$projectRoot\.gradle" | Out-Null
Set-Content -Encoding ASCII "$projectRoot\.gradle\config.properties" "java.home=$(ConvertTo-PropertyPath $env:JAVA_HOME)"
if (Test-Path "$toolsRoot\bin\android.bat") {
    & "$toolsRoot\bin\android.bat" --no-metrics "--sdk=$env:ANDROID_HOME" sdk install platform-tools platforms/android-36 build-tools/36.0.0
    if ($LASTEXITCODE -ne 0) { throw 'Android SDK installation failed.' }
} else {
    & "$toolsRoot\bin\sdkmanager.bat" "--sdk_root=$env:ANDROID_HOME" --licenses
    if ($LASTEXITCODE -ne 0) { throw 'SDK license review failed.' }
    & "$toolsRoot\bin\sdkmanager.bat" "--sdk_root=$env:ANDROID_HOME" 'platform-tools' 'platforms;android-36' 'build-tools;36.0.0'
    if ($LASTEXITCODE -ne 0) { throw 'Android SDK installation failed. Review the SDK licenses and retry.' }
}
foreach ($sdkPackage in @('platform-tools', 'platforms\android-36', 'build-tools\36.0.0')) {
    if (-not (Test-Path "$env:ANDROID_HOME\$sdkPackage\source.properties")) { throw "Missing SDK package: $sdkPackage" }
}
if ($WithModel) {
    $modelRoot = Join-Path $projectRoot '.local\models'
    New-Item -ItemType Directory -Force $modelRoot | Out-Null
    Get-VerifiedDownload 'https://huggingface.co/litert-community/Qwen3-0.6B/resolve/e6687177e00897731e7716267eebd4eba0dde7c2/Qwen3-0.6B.litertlm' "$modelRoot\Qwen3-0.6B.litertlm" 'c5f39b4191926786c3e6668b62af4d6eb15248dfc7a5348252a9b406f33f2108'
}
Write-Host 'Setup complete. From the project root, run:'
Write-Host '  . .\scripts\env.ps1'
Write-Host '  .\gradlew.bat assembleDebug testDebugUnitTest'
Write-Host 'Gradle downloads the pinned project dependencies on the first build.'
