param(
    [Parameter(Mandatory = $true)]
    [string]$Version,

    [string]$RepoPath = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path,

    [string]$AssetsDir,

    [switch]$Draft
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-RequiredFileContent {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Required file missing: $Path"
    }

    return Get-Content -LiteralPath $Path -Raw
}

function Get-GitHubCredential {
    $raw = @"
protocol=https
host=github.com

"@ | git credential-manager get

    if (-not $raw) {
        throw "Unable to read GitHub credential from git credential-manager."
    }

    $credential = $raw | ConvertFrom-StringData
    if (-not $credential.password) {
        throw "GitHub credential does not contain a token."
    }

    return $credential
}

function Get-RepoInfo {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    $remote = (git -C $Path remote get-url origin).Trim()
    if (-not $remote) {
        throw "Unable to read origin remote from $Path"
    }

    $match = [regex]::Match($remote, "github\.com[:/](?<owner>[^/]+)/(?<repo>[^/.]+?)(?:\.git)?$")
    if (-not $match.Success) {
        throw "Origin remote is not a supported GitHub URL: $remote"
    }

    return @{
        Owner = $match.Groups["owner"].Value
        Repo = $match.Groups["repo"].Value
        Remote = $remote
    }
}

function Get-StatusCode {
    param(
        [Parameter(Mandatory = $true)]
        [System.Management.Automation.ErrorRecord]$ErrorRecord
    )

    $response = $ErrorRecord.Exception.Response
    if ($null -eq $response) {
        return $null
    }

    if ($response.StatusCode -is [int]) {
        return [int]$response.StatusCode
    }

    if ($response.StatusCode.value__) {
        return [int]$response.StatusCode.value__
    }

    return $null
}

function Invoke-GitHubJson {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Method,

        [Parameter(Mandatory = $true)]
        [string]$Uri,

        [Parameter(Mandatory = $true)]
        [hashtable]$Headers,

        [object]$Body
    )

    $params = @{
        Method  = $Method
        Uri     = $Uri
        Headers = $Headers
    }

    if ($PSBoundParameters.ContainsKey("Body")) {
        $params.ContentType = "application/json"
        $params.Body = $Body | ConvertTo-Json -Depth 20
    }

    return Invoke-RestMethod @params
}

function Remove-ReleaseAssetIfExists {
    param(
        [Parameter(Mandatory = $true)]
        [psobject]$Release,

        [Parameter(Mandatory = $true)]
        [hashtable]$Headers,

        [Parameter(Mandatory = $true)]
        [string]$FileName,

        [Parameter(Mandatory = $true)]
        [string]$RepoApiBase
    )

    $existingAsset = @($Release.assets | Where-Object { $_.name -eq $FileName }) | Select-Object -First 1
    if ($null -eq $existingAsset) {
        return
    }

    Invoke-RestMethod -Method Delete -Uri "$RepoApiBase/releases/assets/$($existingAsset.id)" -Headers $Headers | Out-Null
}

function Get-UploadContentType {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Extension
    )

    switch ($Extension.ToLowerInvariant()) {
        ".apk" { return "application/vnd.android.package-archive" }
        ".zip" { return "application/zip" }
        ".md" { return "text/markdown; charset=utf-8" }
        ".txt" { return "text/plain; charset=utf-8" }
        default { return "application/octet-stream" }
    }
}

if ($Version.StartsWith("v")) {
    $tag = $Version
    $plainVersion = $Version.Substring(1)
} else {
    $tag = "v$Version"
    $plainVersion = $Version
}

if (-not $AssetsDir) {
    $AssetsDir = Join-Path (Join-Path $RepoPath "release") "$tag-github-assets"
}

$AssetsDir = (Resolve-Path $AssetsDir).Path

$titlePath = Join-Path $AssetsDir "TITLE.txt"
$notesPath = Join-Path $AssetsDir "RELEASE_NOTES.md"
$checksumsPath = Join-Path $AssetsDir "SHA256SUMS.txt"

$title = (Get-RequiredFileContent -Path $titlePath).Trim()
$body = Get-RequiredFileContent -Path $notesPath
$null = Get-RequiredFileContent -Path $checksumsPath

$assetFiles = Get-ChildItem -LiteralPath $AssetsDir -File | Sort-Object Name
if ($assetFiles.Count -eq 0) {
    throw "No files found in assets directory: $AssetsDir"
}

$repoInfo = Get-RepoInfo -Path $RepoPath
$repoApiBase = "https://api.github.com/repos/$($repoInfo.Owner)/$($repoInfo.Repo)"

git -C $RepoPath show-ref --verify --quiet "refs/tags/$tag"
if ($LASTEXITCODE -ne 0) {
    throw "Local tag $tag does not exist in $RepoPath. Commit and tag the release first."
}

$remoteTag = (git -C $RepoPath ls-remote --tags origin "refs/tags/$tag").Trim()
if (-not $remoteTag) {
    throw "Remote tag $tag does not exist on origin. Push the tag before publishing the release."
}

$credential = Get-GitHubCredential
$headers = @{
    Authorization = "token $($credential.password)"
    Accept        = "application/vnd.github+json"
    "User-Agent"  = "tvbox-release-script"
}

$release = $null
try {
    $release = Invoke-GitHubJson -Method Get -Uri "$repoApiBase/releases/tags/$tag" -Headers $headers
} catch {
    $statusCode = Get-StatusCode -ErrorRecord $_
    if ($statusCode -ne 404) {
        throw
    }
}

$releasePayload = @{
    tag_name   = $tag
    name       = $title
    body       = $body
    draft      = [bool]$Draft
    prerelease = $false
}

if ($null -eq $release) {
    $release = Invoke-GitHubJson -Method Post -Uri "$repoApiBase/releases" -Headers $headers -Body $releasePayload
    Write-Host "Created release $tag"
} else {
    $releasePayload.Remove("tag_name")
    $release = Invoke-GitHubJson -Method Patch -Uri "$repoApiBase/releases/$($release.id)" -Headers $headers -Body $releasePayload
    Write-Host "Updated release $tag"
}

$uploadBase = $release.upload_url -replace "\{\?name,label\}", ""

foreach ($file in $assetFiles) {
    Remove-ReleaseAssetIfExists -Release $release -Headers $headers -FileName $file.Name -RepoApiBase $repoApiBase

    $contentType = Get-UploadContentType -Extension $file.Extension
    $uploadUri = "$uploadBase?name=$([uri]::EscapeDataString($file.Name))"
    $bytes = [System.IO.File]::ReadAllBytes($file.FullName)

    $uploadHeaders = @{
        Authorization = $headers.Authorization
        Accept        = $headers.Accept
        "User-Agent"  = $headers["User-Agent"]
        "Content-Type" = $contentType
    }

    Invoke-RestMethod -Method Post -Uri $uploadUri -Headers $uploadHeaders -Body $bytes | Out-Null
    Write-Host "Uploaded $($file.Name)"
}

$finalRelease = Invoke-GitHubJson -Method Get -Uri "$repoApiBase/releases/tags/$tag" -Headers $headers

Write-Host ""
Write-Host "Release URL: $($finalRelease.html_url)"
Write-Host "Draft: $($finalRelease.draft)"
Write-Host "Assets:"
foreach ($asset in $finalRelease.assets) {
    Write-Host " - $($asset.name)"
}
