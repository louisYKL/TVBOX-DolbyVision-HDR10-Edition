param(
    [switch]$SkipJdk,
    [switch]$SkipSdk
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$runtimeRoot = Join-Path $repoRoot '_runtime'
$downloads = Join-Path $runtimeRoot 'downloads'
$jdkRoot = Join-Path $runtimeRoot 'jdk\temurin11'
$jdk17Root = Join-Path $runtimeRoot 'jdk\temurin17'
$sdkRoot = Join-Path $runtimeRoot 'android-sdk'

New-Item -ItemType Directory -Force $downloads, $jdkRoot, $jdk17Root, $sdkRoot | Out-Null

function Download-Once([string]$Uri, [string]$Target) {
    if (-not (Test-Path $Target)) {
        Write-Host "Downloading $Uri"
        Invoke-WebRequest -Uri $Uri -OutFile $Target -UseBasicParsing
    }
}

if (-not $SkipJdk) {
    $jdkZip = Join-Path $downloads 'OpenJDK11U-jdk_x64_windows_hotspot_11.0.32.1_1.zip'
    Download-Once 'https://github.com/adoptium/temurin11-binaries/releases/download/jdk-11.0.32.1%2B1/OpenJDK11U-jdk_x64_windows_hotspot_11.0.32.1_1.zip' $jdkZip
    if (-not (Get-ChildItem $jdkRoot -Directory -ErrorAction SilentlyContinue)) {
        Expand-Archive -LiteralPath $jdkZip -DestinationPath $jdkRoot -Force
    }

    $jdk17Zip = Join-Path $downloads 'OpenJDK17U-jdk_x64_windows_hotspot_17.0.13_11.zip'
    Download-Once 'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.13%2B11/OpenJDK17U-jdk_x64_windows_hotspot_17.0.13_11.zip' $jdk17Zip
    if (-not (Get-ChildItem $jdk17Root -Directory -ErrorAction SilentlyContinue)) {
        Expand-Archive -LiteralPath $jdk17Zip -DestinationPath $jdk17Root -Force
    }
}

if (-not $SkipSdk) {
    $cmdlineZip = Join-Path $downloads 'commandlinetools-win-11076708_latest.zip'
    $cmdlineRoot = Join-Path $runtimeRoot 'cmdline-tools'
    Download-Once 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' $cmdlineZip
    if (-not (Test-Path (Join-Path $cmdlineRoot 'latest\bin\sdkmanager.bat'))) {
        $extractRoot = Join-Path $runtimeRoot 'cmdline-tools-extract'
        Remove-Item $extractRoot -Recurse -Force -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Force $extractRoot | Out-Null
        Expand-Archive -LiteralPath $cmdlineZip -DestinationPath $extractRoot -Force
        New-Item -ItemType Directory -Force $cmdlineRoot | Out-Null
        Move-Item (Join-Path $extractRoot 'cmdline-tools') (Join-Path $cmdlineRoot 'latest')
        Remove-Item $extractRoot -Recurse -Force
    }

    $env:ANDROID_HOME = $sdkRoot
    $env:ANDROID_SDK_ROOT = $sdkRoot
    $jdk17Candidate = Get-ChildItem $jdk17Root -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName 'bin\java.exe') } |
        Select-Object -First 1
    if ($null -eq $jdk17Candidate) {
        throw "A Java 17 runtime is required for the Android command-line tools under $jdk17Root"
    }
    $env:JAVA_HOME = $jdk17Candidate.FullName
    $env:ANDROID_USER_HOME = Join-Path $runtimeRoot 'android-user-home'
    $env:HOME = Join-Path $runtimeRoot 'home'
    $env:USERPROFILE = $env:HOME
    $env:TEMP = Join-Path $runtimeRoot 'tmp'
    $env:TMP = $env:TEMP
    New-Item -ItemType Directory -Force $env:ANDROID_USER_HOME, $env:HOME, $env:TEMP | Out-Null

    $sdkManager = Join-Path $cmdlineRoot 'latest\bin\sdkmanager.bat'
    $licenseInput = ("y`n" * 30)
    $licenseInput | & $sdkManager --sdk_root=$sdkRoot --licenses | Out-Null
    & $sdkManager --sdk_root=$sdkRoot 'platform-tools' 'platforms;android-34' 'build-tools;34.0.0'
    if ($LASTEXITCODE -ne 0) { throw "sdkmanager failed with exit code $LASTEXITCODE" }
}

Write-Host "Runtime prepared under $runtimeRoot"
