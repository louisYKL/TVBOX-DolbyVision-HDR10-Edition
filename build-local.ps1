param(
    [string[]]$Tasks = @(
        ":app:assembleNormalRelease",
        ":app:assembleJava64Release",
        ":app:assembleHisenseRelease"
    )
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$runtimeRoot = Join-Path $repoRoot "_runtime"
$jdkParent = Join-Path $runtimeRoot "jdk\\temurin11"
$jdkRoot = Join-Path $jdkParent "jdk-11.0.32.1+1"
$sdkRoot = Join-Path $runtimeRoot "android-sdk"
$gradleHome = Join-Path $runtimeRoot "gradle-home"
$androidUserHome = Join-Path $runtimeRoot "android-user-home"
$androidSdkHome = Join-Path $runtimeRoot "android-home"
$homeRoot = Join-Path $runtimeRoot "home"
$localAppData = Join-Path $runtimeRoot "localappdata"
$appData = Join-Path $runtimeRoot "appdata"
$tmpRoot = Join-Path $runtimeRoot "tmp"
$androidHomeDir = Join-Path $homeRoot ".android"
$keystoreSource = Join-Path $androidSdkHome "debug.keystore"
$keystoreTargets = @(
    (Join-Path $androidUserHome "debug.keystore"),
    (Join-Path $androidHomeDir "debug.keystore")
)

$requiredDirs = @(
    $androidUserHome,
    $androidSdkHome,
    $homeRoot,
    $localAppData,
    $appData,
    $tmpRoot,
    $androidHomeDir
)

if (-not (Test-Path (Join-Path $jdkRoot "bin\\java.exe"))) {
    $detectedJdk = Get-ChildItem $jdkParent -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName "bin\\java.exe") } |
        Select-Object -First 1
    if ($null -ne $detectedJdk) {
        $jdkRoot = $detectedJdk.FullName
    }
}

foreach ($dir in $requiredDirs) {
    New-Item -ItemType Directory -Force $dir | Out-Null
}

if (Test-Path $keystoreSource) {
    foreach ($target in $keystoreTargets) {
        if (-not (Test-Path $target)) {
            Copy-Item $keystoreSource $target -Force
        }
    }
}

$env:JAVA_HOME = $jdkRoot
$env:GRADLE_USER_HOME = $gradleHome
$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_SDK_ROOT = $sdkRoot
$env:ANDROID_USER_HOME = $androidUserHome
$env:HOME = $homeRoot
$env:USERPROFILE = $homeRoot
$env:HOMEDRIVE = ([System.IO.Path]::GetPathRoot($homeRoot)).TrimEnd('\')
$env:HOMEPATH = $homeRoot.Substring($env:HOMEDRIVE.Length)
$env:LOCALAPPDATA = $localAppData
$env:APPDATA = $appData
$env:TEMP = $tmpRoot
$env:TMP = $tmpRoot

$sdkDirForProperties = $sdkRoot -replace "\\", "/"
Set-Content -Path (Join-Path $repoRoot "local.properties") -Encoding ASCII -Value "sdk.dir=$sdkDirForProperties"

Write-Host "Using JAVA_HOME=$env:JAVA_HOME"
Write-Host "Using ANDROID_HOME=$env:ANDROID_HOME"
Write-Host "Using GRADLE_USER_HOME=$env:GRADLE_USER_HOME"

& (Join-Path $repoRoot "gradlew.bat") @Tasks --console=plain
exit $LASTEXITCODE
