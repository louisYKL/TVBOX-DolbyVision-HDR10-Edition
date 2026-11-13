param([switch]$Publish)
$ErrorActionPreference = 'Stop'
$PSDefaultParameterValues['Invoke-RestMethod:Proxy'] = 'http://127.0.0.1:7890'
$repoRoot = 'E:\tvbox\TVBoxOS-main'
$ownerRepo = 'louisYKL/TVBOX-DolbyVision-HDR10-Edition'
$tag = 'v0.3.2'
$stage = Join-Path $repoRoot '0.3.2'
$receiptDir = Join-Path $repoRoot '_diagnostics\candidate-0.3.2'
$verified = @(Get-Content (Join-Path $receiptDir 'apk-verification.json') -Raw | ConvertFrom-Json)
if ($verified.Count -ne 3) { throw 'Three verified APKs are required' }
$tests = @(Get-Content (Join-Path $receiptDir 'unit-test-receipt.json') -Raw | ConvertFrom-Json)
if ($tests.Count -ne 2 -or @($tests | Where-Object { $_.tests -lt 1 -or $_.failures -ne 0 -or $_.errors -ne 0 }).Count) {
    throw 'Both app and player tests must pass'
}
foreach ($entry in $verified) {
    if ($entry.Version -ne '0.3.2' -or $entry.VersionCode -ne 30002 -or $entry.Debuggable -ne $false -or
        $entry.SigningCertificateSha256 -ne '7c05de517bc51e22f782b2369704c74d459aa15d4e0074f1f2c3491cc76d6bbc' -or
        (Get-FileHash -LiteralPath $entry.Apk -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry.Sha256) {
        throw 'APK verification receipt no longer matches'
    }
    Copy-Item -LiteralPath $entry.Apk -Destination $stage -Force
}
$checksums = foreach ($entry in $verified) { $entry.Sha256 + '  ' + (Split-Path $entry.Apk -Leaf) }
[IO.File]::WriteAllLines((Join-Path $stage 'SHA256SUMS.txt'), $checksums, [Text.UTF8Encoding]::new($false))
$assets = @($verified | ForEach-Object { Join-Path $stage (Split-Path $_.Apk -Leaf) }) +
    @((Join-Path $stage 'SHA256SUMS.txt'), (Join-Path $stage 'RELEASE_NOTES.md'))
if (!$Publish) {
    $assets | ForEach-Object { Get-Item -LiteralPath $_ } | Select-Object Name, Length
    return
}
$sourceHead = (& git -C $repoRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Cannot read source revision' }
$tagHead = (& git -C $repoRoot rev-list -n 1 $tag).Trim()
if ($LASTEXITCODE -ne 0 -or $tagHead -ne $sourceHead) { throw 'Release tag must match committed sources' }
$dirty = @(& git -C $repoRoot status --porcelain --untracked-files=no)
if ($dirty.Count) { throw 'Tracked source changes remain uncommitted' }
$credentialInput = "protocol=https`nhost=github.com`n`n"
$credential = $credentialInput | git -C $repoRoot credential fill 2>$null
if ($LASTEXITCODE -ne 0) { throw 'GitHub credentials unavailable' }
$passwordLine = @($credential | Where-Object { $_.StartsWith('password=') })
if ($passwordLine.Count -ne 1) { throw 'GitHub credentials unavailable' }
$token = $passwordLine[0].Substring(9)
$headers = @{ Authorization = 'Bearer ' + $token; Accept = 'application/vnd.github+json'; 'User-Agent' = 'TVBox-local-release' }
try {
    $release = $null
    try { $release = Invoke-RestMethod "https://api.github.com/repos/$ownerRepo/releases/tags/$tag" -Headers $headers -TimeoutSec 30 }
    catch { if ([int]$_.Exception.Response.StatusCode -ne 404) { throw } }
    if ($null -eq $release) {
        $body = @{ tag_name=$tag; target_commitish=$sourceHead; name='0.3.2 | WebDAV HTTPS domain and IP support'; body=(Get-Content (Join-Path $stage 'RELEASE_NOTES.md') -Raw); draft=$true; prerelease=$false } | ConvertTo-Json -Depth 4
        $release = Invoke-RestMethod "https://api.github.com/repos/$ownerRepo/releases" -Method Post -Headers $headers -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 30
    }
    if (!$release.draft) { throw 'Release is already public; do not overwrite published assets automatically' }
    foreach ($path in $assets) {
        $name = Split-Path $path -Leaf
        $existing = @($release.assets | Where-Object { $_.name -eq $name })
        foreach ($old in $existing) {
            Invoke-RestMethod ("https://api.github.com/repos/$ownerRepo/releases/assets/" + $old.id) -Method Delete -Headers $headers -TimeoutSec 30 | Out-Null
        }
        $uploadUri = "https://uploads.github.com/repos/$ownerRepo/releases/" + $release.id + '/assets?name=' + [Uri]::EscapeDataString($name)
        $asset = Invoke-RestMethod $uploadUri -Method Post -Headers $headers -ContentType 'application/octet-stream' -InFile $path -TimeoutSec 180
        if ($asset.size -ne (Get-Item -LiteralPath $path).Length) { throw "Uploaded size mismatch: $name" }
        Write-Host "Uploaded $name"
    }
    $body = @{ body=(Get-Content (Join-Path $stage 'RELEASE_NOTES.md') -Raw); draft=$false; prerelease=$false; make_latest='true' } | ConvertTo-Json
    $release = Invoke-RestMethod ("https://api.github.com/repos/$ownerRepo/releases/" + $release.id) -Method Patch -Headers $headers -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 30
    $receipt = [pscustomobject]@{ tag=$tag; commit=$sourceHead; url=$release.html_url; draft=$release.draft; assets=@($release.assets | Select-Object name,size,digest,browser_download_url) }
    $receipt | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $receiptDir 'github-release.json') -Encoding utf8
    Write-Host $release.html_url
} finally {
    $headers.Clear(); $token=$null; $credential=$null; $passwordLine=$null
}
