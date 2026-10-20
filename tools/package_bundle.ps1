param(
    [Parameter(Mandatory=$true)][string]$KeyPath,
    [int]$Version = 0,
    [ValidateRange(0, 2147483647)][int]$MinAppVersion = 0,
    [string]$UrlBase = 'https://raw.githubusercontent.com/UNCONFIGURED/emoje-tools/main/bundle'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Push-Location $projectRoot
try {
    $assets = Join-Path $projectRoot 'app/src/main/assets'
    $bundleAssets = Join-Path $assets 'bundle'
    $current = Get-Content -LiteralPath (Join-Path $bundleAssets 'manifest.json') -Raw | ConvertFrom-Json
    if ($MinAppVersion -eq 0) { $MinAppVersion = [int]$current.min_app_version }
    if ($Version -eq 0) { $Version = [int]$current.bundle_version + 1 }
    if ($Version -le [int]$current.bundle_version) { throw 'Bundle version must increase' }
    $publicKey = & python -c "import base64; from cryptography.hazmat.primitives import serialization; import sys; key=serialization.load_pem_private_key(open(sys.argv[1],'rb').read(),password=None); print(base64.b64encode(key.public_key().public_bytes(serialization.Encoding.Raw,serialization.PublicFormat.Raw)).decode())" $KeyPath
    if ($LASTEXITCODE -ne 0) { throw 'Public key export failed' }
    $embeddedKey = (Get-Content -LiteralPath (Join-Path $assets 'bundle_pubkey.b64') -Raw).Trim()
    if ($publicKey.Trim() -cne $embeddedKey) { throw 'Signing key does not match the embedded public key; trust rotation requires an explicit APK change' }
    & npm.cmd run bundle:js
    if ($LASTEXITCODE -ne 0) { throw 'JS build failed' }
    & python tools/build_bundle.py --src bundle_src --out bundle_dist --key $KeyPath --version $Version --min-app $MinAppVersion --url-base $UrlBase
    if ($LASTEXITCODE -ne 0) { throw 'Bundle signing failed' }
    & python tools/verify_bundle.py --dir bundle_dist --pubkey-b64 $publicKey
    if ($LASTEXITCODE -ne 0) { throw 'Bundle verification failed' }
    Copy-Item -LiteralPath "bundle_dist/bundle_v$Version.zip" -Destination $bundleAssets
    Copy-Item -LiteralPath 'bundle_dist/manifest.json' -Destination $bundleAssets
    $expectedDirectory = [IO.Path]::GetFullPath((Join-Path $projectRoot 'app/src/main/assets/bundle'))
    Get-ChildItem -LiteralPath $bundleAssets -File | Where-Object {
        $_.Name -match '^bundle_v[0-9]+\.zip$' -and $_.Name -ne "bundle_v$Version.zip"
    } | ForEach-Object {
        if ($_.Directory.FullName -ne $expectedDirectory) { throw 'Unexpected bundle directory' }
        Remove-Item -LiteralPath $_.FullName
    }
} finally { Pop-Location }
