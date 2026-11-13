$ErrorActionPreference = 'Stop'
$repoRoot = 'E:\tvbox\TVBoxOS-main'
$diagnostics = Join-Path $repoRoot '_diagnostics\candidate-0.3.2'
$env:JAVA_HOME = Join-Path $repoRoot '_runtime\jdk\temurin11\jdk-11.0.31+11'
$apksigner = Join-Path $repoRoot '_runtime\android-sdk\build-tools\34.0.0\apksigner.bat'
$aapt = Join-Path $repoRoot '_runtime\android-sdk\build-tools\34.0.0\aapt.exe'
$certificate = '7c05de517bc51e22f782b2369704c74d459aa15d4e0074f1f2c3491cc76d6bbc'
$variants = @(
    @{ Flavor = 'normal'; Variant = 'java32'; Abi = 'armeabi-v7a' },
    @{ Flavor = 'java64'; Variant = 'java64'; Abi = 'arm64-v8a' },
    @{ Flavor = 'hisense'; Variant = 'hisense32'; Abi = 'armeabi-v7a' }
)
Add-Type -AssemblyName System.IO.Compression.FileSystem
$receipts = foreach ($variant in $variants) {
    $apk = Join-Path $repoRoot ('_build-v0.3.2\app\outputs\apk\' + $variant.Flavor + '\release\TVBox_v0.3.2_' + $variant.Variant + '.apk')
    $signature = & $apksigner verify --verbose --print-certs $apk 2>&1
    if ($LASTEXITCODE -ne 0) { throw "APK signature verification failed: $apk" }
    $signature | Set-Content -LiteralPath (Join-Path $diagnostics ($variant.Variant + '-certificate.txt')) -Encoding UTF8
    $certificateLine = ($signature | Select-String 'Signer #1 certificate SHA-256 digest:').Line
    if (!$certificateLine -or ($certificateLine.Split(':')[-1].Trim().ToLowerInvariant() -ne $certificate)) {
        throw "Historical certificate mismatch: $apk"
    }
    $badging = & $aapt dump badging $apk 2>&1
    if ($LASTEXITCODE -ne 0) { throw "APK metadata inspection failed: $apk" }
    $badging | Set-Content -LiteralPath (Join-Path $diagnostics ($variant.Variant + '-badging.txt')) -Encoding UTF8
    $package = ($badging | Select-String '^package:').Line
    if ($package -notmatch "name='com.github.tvbox.osc'" -or $package -notmatch "versionCode='30002'" -or $package -notmatch "versionName='0.3.2'") {
        throw "APK version/package mismatch: $apk"
    }
    if ($badging | Select-String '^application-debuggable') { throw "APK is debuggable: $apk" }
    $archive = [System.IO.Compression.ZipFile]::OpenRead($apk)
    try {
        $abis = @($archive.Entries | ForEach-Object { if ($_.FullName -match '^lib/([^/]+)/') { $Matches[1] } } | Sort-Object -Unique)
    } finally { $archive.Dispose() }
    if ($abis.Count -ne 1 -or $abis[0] -ne $variant.Abi) { throw "Unexpected APK ABIs: $apk" }
    [pscustomobject]@{
        Version = '0.3.2'; VersionCode = 30002; Variant = $variant.Variant
        Abi = $variant.Abi; Apk = $apk; Bytes = (Get-Item -LiteralPath $apk).Length
        Sha256 = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
        SigningCertificateSha256 = $certificate; Debuggable = $false
    }
}
$receipts | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $diagnostics 'apk-verification.json') -Encoding UTF8
$receipts | Format-Table Variant, Abi, Bytes, Sha256 -AutoSize





