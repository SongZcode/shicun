# Shicun (拾存)

Public repository: [SongZcode/shicun](https://github.com/SongZcode/shicun).

An on-device Douyin and Bilibili material downloader. Douyin Favorite Stickers and Saved/Liked Works have account collection screens with actual thumbnails, preview, selection, Save and Share. Bilibili supports BV/av/ep/ss/video/PGC/short links, official website login, part/episode/quality selection and local DASH muxing. Its account page provides favorite folders and recent likes, loading cover/title pages immediately; media details are fetched only when opened or saved. Static WebP/HEIC/AVIF sharing offers temporary compatible JPEG/PNG copies while retaining originals and motion. Sticker search is omitted. The app is in development; no released-version compatibility is required. Each platform keeps its own encrypted session. Downloads save directly to the system gallery or audio collection. See Status for device acceptance and remaining checks.

## Start Here

1. [Product behavior](docs/PRODUCT_SCOPE.md): agreed scope and user experience.
2. [Current status](docs/STATUS.md): implemented, verified and next work.
3. [Development](docs/DEVELOPMENT.md): code map, protocol contracts, bundle and device checks.

This public repository contains the current product source and summarized acceptance results. Private development history, account screenshots, raw device evidence, signing keys and local configuration are not included.

## Build And Check

Requires JDK 17, Android SDK Platform 35 / Build Tools 34+, Node/npm and Python with `cryptography` for bundle packaging. Set the SDK path in untracked `local.properties`. The scripts use the existing local JDK/Gradle cache when available, otherwise the Gradle wrapper.

```powershell
npm.cmd ci --ignore-scripts  # once, or after package-lock changes
./tools/build.ps1           # debug APK + JVM tests
./tools/build.ps1 -Check    # also lint, test APK and local signing tests
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

Current tested version: EmojeTools 0.14.1, code 26 / bundle 23. Build the APK using the commands above; local test APKs are not committed. Bilibili batch saving uses the first P at the highest compatible quality; Recent Likes is a bounded recent list. Continuing a download reuses completed files and restarts an unfinished file. See [acceptance and limits](docs/verification/bilibili.md#closeout).

UI/device work also requires installing both APKs and running the relevant isolated tests; see [device checks](docs/DEVELOPMENT.md#device-checks). Do not clear production app data.

## Rule Changes

Change paths/parameters in `bundle_src/platforms/{platform}.json`, fields in `bundle_src/parsers/{platform}.json`, signing source in `tools/sign/{platform}.entry.js`. Run `npm.cmd run bundle:js` after JS changes, then package with the existing signing key:

```powershell
./tools/package_bundle.ps1 -KeyPath "$env:LOCALAPPDATA/EmojeTools/offline-keys/bundle_signing.pem"
```

The script increments the bundle version, verifies the package and keeps only the active built-in ZIP. It refuses an unrelated signing key. Do not generate a replacement key during normal development. Runtime verified update/rollback remains in place. Online GitHub sources are not configured yet; the embedded URL is a placeholder.

The minimum app version carries forward from the active manifest. When a rule update requires a new native runtime contract, set `-MinAppVersion` to its versionCode. Bundle 23 requires app code 25 for Bilibili PGC and account collections; Douyin login remains separate.

See [third-party notices](docs/THIRD_PARTY_NOTICES.md).
