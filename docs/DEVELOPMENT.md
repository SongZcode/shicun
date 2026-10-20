# Development

Read once before editing; subsequent work should open only relevant code/evidence. [Product Scope](PRODUCT_SCOPE.md) controls UX; [Status](STATUS.md) separates delivered behavior from candidates.

## Code Map

Kotlin root: `app/src/main/java/com/emojetools/app/`.

| Area | Entry |
| --- | --- |
| UI and selections | `ui/MainActivity.kt`, `MainScreen.kt`, `PostSelection.kt`, `CommentSelection.kt`, `CommentMediaViewer.kt` |
| Favorite Stickers | `ui/FavoriteStickersActivity.kt`, `FavoriteStickersScreen.kt`, `FavoritePreviews.kt`, `core/store/FavoriteCollection.kt`, `core/download/FavoriteDownloader.kt` |
| Saved/Liked Works | `ui/AccountWorksActivity.kt`, `AccountWorksScreen.kt`, `platform/douyin/AccountWorksFetcher.kt`, `core/store/AccountWorksCollection.kt`, `core/download/WorkBatchDownloader.kt` |
| Task execution/recovery | `core/task/TaskCoordinator.kt`, `TaskService.kt` |
| Adapter/models | `platform/PlatformAdapter.kt`, `platform/douyin/DouyinAdapter.kt`, `core/Models.kt` |
| Bilibili | `platform/bilibili/BilibiliAdapter.kt`, `bundle_src/platforms/bilibili.json`, `bundle_src/parsers/bilibili.json`, `tools/sign/bilibili.entry.js` |
| HTTP/local JS | `core/net/SignedHttpClient.kt`, `core/js/JsEngine.kt` |
| Rule installation/rollback | `core/bundle/BundleManager.kt` |
| Files/index/system publication | `core/download/`, `core/store/AssetStore.kt`, `MediaPublisher.kt`, `MediaSharing.kt` |
| Compatible image sharing | `core/store/CompatibleImages.kt`, `ui/ShareOptions.kt` |
| Ledger/checkpoints | `core/ledger/FetchLedger.kt`, `platform/douyin/CommentFetcher.kt` |
| Favorite collection protocol/tests | `platform/douyin/FavoriteStickerFetcher.kt`, production bundle JSON; `FavoriteStickerFetcherTest.kt`, `FavoriteCollectionDeviceTest.kt`, `FavoriteStickersDeviceTest.kt` |

Keep `PlatformAdapter` signatures and the existing responsibility boundaries. The app is unreleased: obsolete development records can be discarded; do not build old-version migrations. Do not remove supported API26-28 storage, current task recovery or verified bundle rollback as "legacy".

Comment material filtering is presentation-only: keep text comments in matching/checkpoints/cache. `CommentSelection` renders available media in fixed square grid tracks; `CommentMediaViewer` shares the existing preview download cache and `MediaPreview` decoder. It stops video/animation and cancels rendering on dismissal. Comment retries validate persisted successful output URIs, retain them, process only pending assets and report totals for the original selection. New selections clear previous output state.

Favorite collections keep their own cursor, visited cursors, account key, session/rule fingerprint and selections. The cache is replaced on session changes. Favorite output tasks have an empty work ID and a real account-derived collection key; their selected-asset snapshots are separate from work results and comment ledgers. The existing foreground service, downloader, verification index, publisher and URI sharing deliver their files. Older rollback bundles can still run the app; bundles without the collection capability show an unsupported-rule error on this page.

## Platform Contracts

Bilibili uses a dedicated local JS engine and HTTP adapter, without Douyin cookies/session. `DownloadMode.BILIBILI` keeps existing Douyin modes intact. Real `PostRef` retains aid/bvid/cid; storage identity is `bilibili:<aid>:<cid>`, preserving numeric Douyin identities and directory naming. DASH assets carry a neutral `dash_mux` transform and audio URL set; native `MediaTools.muxTracks` copies compressed AVC/AAC samples with their timestamps. Direct audio/cover and transformed video use the existing index, publisher and sharing. Missing Bilibili rules after rollback produce an unsupported-rule error.

PGC references additionally retain actual episode/season IDs. `selectPart` resolves the episode's aid/bvid/cid together; never copy a new cid onto the old episode. Signed parser/rules own season/playback paths and whole/preview/DRM mappings. A full-length `timelength` can also appear on previews, so require whole playback state and non-preview flags. Encrypted samples are also rejected by native muxing. Bilibili's configured download profile separates idle and total-call timeouts and per-track size limits; Douyin keeps its existing limits.

`AccountWorksActivity/Screen`, `AccountWorksCollection` and `WorkBatchDownloader` are shared across platforms. Account cache tags include platform/session/rule version, account keys include platform, and Bilibili favorites persist separate folder snapshots/selections. List requests return only summaries; render before cache writes and request another page only on demand. Collection refs retain real aid/bvid without cid; opening/saving resolves the default part and stores the canonical ref. Refresh uses that canonical ref. Recent Likes is a bounded endpoint whose ignored page parameter was verified on device; it is not presented as historical paging. `FavoritePreviews` uses platform-aware headers through `downloadPreview`, three concurrent cover loads and off-main decoding.

`LoginActivity` selects the platform's signed login configuration. Bilibili displays the official H5 form with the configured `login_browser` mobile UA and validates the captured session with native nav HTTP before encrypted persistence; API/CDN retain their desktop UA. Bilibili cookies are sent only to the configured API origin, never to Douyin or CDN downloads. `SessionStore` keeps separate encrypted namespaces; scoped cookie expiration replaces global cookie deletion. Cookie origins/names/domains and login-state fields are signed JSON. Logging in from a work re-fetches its quality list while retaining valid selections. No credential or cookie contents belong in logs/evidence.

`ShareOptions` is shared by task, collection and stored-file sharing. Original remains the default. `CompatibleImages` copies static WebP/HEIC/AVIF to temporary JPEG/PNG under `cache/materials/.compatible`, within the existing FileProvider scope and 24-hour staging cleanup. It preserves dimensions/transparency, rejects excessive decode dimensions, skips animated WebP/decoded animation/image sequences and leaves video/GIF untouched. No publication/index entry or overwrite is created for conversion. Unsupported decoders or failed conversions return explicit errors so the user can choose Original.

- Kotlin / minSdk 26 / targetSdk 35. All logic on device; only signed static bundles may be remote. HTTP + local signing, no browser-driven API execution. WebView is for login/local JS.
- Platform constants/signature algorithms -> JS; paths/ordered parameters -> `bundle_src/platforms/douyin.json`; raw field mappings -> `bundle_src/parsers/douyin.json`. Kotlin handles transport/state/storage, not protocol literals.
- `buildUrl(path, params, ctx)` returns the final URL. Kotlin sends it unchanged: no append, sort or re-encoding. Exactly one a_bogus; protected paths/UIFID/secsdk rules stay in the bundle. Secsdk covers the final parameter order, not sorted pairs.
- Per-operation HTTP `method`, ordered `form` pairs and optional `headers` live in the platform JSON. `RequestRecipe` resolves variables and encodes forms; form fields are separate from the signed query. Response streaming and JSON decoding run on IO. Existing `SignedHttpClient.get` calls retain their behavior; account collections use `request`.
- Web login/account requests use the saved WebView UA and encrypted session. Comment/reply operations select the signed `anonymous_comments` profile: its origin, UA, common query and identity recipe live in JS/JSON. `SessionStore` persists a locally generated anonymous identity separately from login. Anonymous requests do not read/send account cookies or UIFID; empty bodies are BLOCKED. Session cookie names stay configured and secrets stay out of logs.
- Comment cache/checkpoint fingerprints include the login fingerprint, anonymous identity and bundle version. Account-collection fingerprints retain their existing session behavior. `HttpTransport.consume` keeps cancellation attached through body reads; `Downloader` removes partial files when paused or disconnected.
- Empty HTTP 200, invalid schema, 403 and exhausted retries are failures. Empty authenticated web responses require NEED_LOGIN, not zero results. Never catch an API failure and return an empty success list.
- Current comment rules: count_top=50, count_reply=20, primary delay 400ms, reply delay 250ms, 3 attempts with fresh signatures, 60-90s cooldown and bounded recovery. Values remain configuration, not Kotlin constants.
- Always use the response cursor. Default limit_top=2000 unique top comments; 0 removes that limit, not the 200-page cap. Count the complete last page before stopping. Replies have independent 20-page/parent and 5000-total caps. Do not apply comment counts to account-collection APIs.
- Ledger has separate top/reply termination, using `error > stalled_cursor > replies_cap > limit_reached > page_cap > parent_cap > server_end`; record every reached cap in `caps_hit`. API/detail totals are observations, not evidence of full coverage.
- Ledger stable fields: schema, platform, aweme_id, started_at, finished_at, limits, counters, totals, termination, caps_hit, errors. Canonical UTF-8 JSON: sorted keys, compact separators, non-ASCII preserved; SHA-256 lowercase hex; checksum_algo=`sha256/json-canonical-v1`. Exclude checksum and extras; checkpoint extras have their own checksum. Keep current schema/counter definitions in `FetchLedger`.
- Every saved asset retains SHA-1 + bytes. Deduplicate by underlying resource identity; try mirrors with the configured Referer and verify magic/decodability. Use comment original-image fields, not watermarked download fields. Preserve all returned images/motion.
- Staging directory: `<root>/douyin/<title first20>_<work id first8>/`. ASCII filenames: video `v01_1080p.mp4`, images `i03_<hash6>.jpg`, cover `c01.jpg`, audio `a01_<id last6>.m4a`, comments `01_T_<cid last6>.gif` or `02_R_...`; extension follows actual bytes. Reuse current naming/publishing code.
- No GPL-3.0/AGPL-3.0, proprietary research SDK/binaries/configuration, backend signer or Root/official-app process dependency. See [notices](THIRD_PARTY_NOTICES.md).

The original full technical reference is `F:\dsh_workspace\douyin_emoji\docs\APP_开发交接_多平台聚合_v2.md`. Consult relevant sections only when changing the underlying contract: architecture/models 2-3, protocol 5, login/download 7-8, throttling 13, license 17, finalized ledger/naming/bundle 19. Later user decisions in Product Scope override historical UI/scope examples.

## Bundle Workflow

Use the README packaging command with the existing offline private key. Do not rotate the embedded trust key as a convenience. `bundle_src/sign/douyin.js` is generated from `tools/sign/douyin.entry.js`; package after rule changes so the APK actually receives them.

`package_bundle.ps1` inherits `min_app_version` by default; `-MinAppVersion` raises it when native support is needed. Bundle 23 uses minimum app code 25 for Bilibili PGC/collections. Runtime rollback remains verified; older bundles show an unsupported-rule state for capabilities they do not define.

Ed25519 payload is exactly seven UTF-8 lines in this order, without a trailing newline:

```text
v1
{bundle_version}
{min_app_version}
{published_at}
{zip_sha256}
{files_digest}
{url}
```

`files_digest` hashes the path-sorted file list with canonical compact JSON. Verify signature, archive hash, exact file set and each file's hash/bytes before activation. Only the current ZIP is embedded; downloaded runtime rollback versions are managed separately. Online sources remain unconfigured.

## Device Checks

Shared workstation tools and MCP entrypoints: `F:\Tools\README.md`. Portable tools
are under `F:\Tools`; `tools/build.ps1` selects the existing JDK 17 for Gradle 8.2
and restores the caller's Java environment, independent of tooling JDK 21.

`LoginShareDeviceTest` uses reserved fixture cookie domains and isolated encrypted stores to check persistence/scoped logout, then generated WebP fixtures to check opaque JPEG, transparent PNG, animated WebP/video preservation, original hashes, readable share grants and conversion failure cleanup. A real authenticated Bilibili quality/download check requires the user to complete the official login on the phone; credentials are never test fixtures.

Bilibili: `BilibiliDeviceTest#muxPreservesCompressedSamplesAndPlatformStorage` checks native mux sample preservation and storage isolation. `#publicVideoPartsDownloadSaveReuseAndShare` requires `-e bilibili_live true`; it uses production rules/CDN and an isolated database/output directory, checks actual parts/quality, video/audio/cover publication, SHA-1 readback, URI reuse and readable sharing, then removes only its own published rows. CDN requests require the standard desktop-browser UA in the Bilibili JSON; the initial mobile UA returned HTTP 403. Keep this rule in the bundle.

`BilibiliLibraryDeviceTest#realCollectionsAndWholeMemberEpisode` requires `-e bilibili_library true`. It uses production signed rules to read actual folder/like summaries, preserve real sparse refs through batch snapshots, resolve ep/ss/short-link aliases, switch episode identities, reject an anonymous member preview and save/read back/decode/reuse/share a whole member episode in isolated storage. `-e collections_only true` instead requests two-item favorite pages and saves/reuses a short actual collection work through the batch path. Only new test-owned MediaStore rows are removed. The obsolete test-only PGC recipe/probe was removed after formal integration.

Closeout: `#longMemberDownloadPausesAndContinuesInBackground` requires `-e bilibili_closeout true`. It uses the existing fixture application's isolated database/material root with production rules and the saved login. A real long member video runs in `TaskService`, returns to Home, verifies the foreground notification, pauses during actual partial-file IO with the screen off, verifies selection/persistence/partial cleanup, then continues to whole-file publication and end-frame/hash readback while the screen stays off. It removes only its task/new publication and restores the screen. Notification screenshot: external app files `closeout-background.png`. This is short-duration screen-off acceptance, not prolonged Doze/OEM coverage or byte-range resume.

Run `./tools/build.ps1 -Check` for runtime/protocol changes. It builds both APKs, runs JVM/signing tests and lint. UI and lifecycle changes also need relevant device checks; ordinary tests use the isolated fixture application/database and do not call Douyin. Live preflights require explicit opt-in and are documented with their evidence.

```powershell
adb devices -l
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class com.emojetools.app.TaskHistoryDeviceTest com.emojetools.app.test/com.emojetools.app.M3TestRunner
```

Replace the class filter with the relevant classes or omit it for the fixture suite. Shared ADB: `F:\Tools\MobileMCP\android-sdk\platform-tools\adb.exe`. Detect the connected device; do not assume old serials/PIDs/users. Never `pm clear` the production package for testing.

For process-recovery changes, run `M3DeviceTest#externalProcessRecovery` with `-e recovery_phase seed`, force-stop the app, then rerun with `-e recovery_phase verify`. This deliberately interrupts the app; do it when no user task is running. It uses fixture task data.

For UI changes inspect actual phone screenshots, normal/narrow layouts, keyboard/bottom controls, long lists, dynamic previews, selection retention and thumbnail stability. Report what passed and what remains unverified. Keep concise sanitized acceptance evidence under `docs/verification/` and update Status; no research-style round reports.

Favorite fixtures: run `FavoriteCollectionDeviceTest` (isolated database/cache; only owned MediaStore rows are removed). Live acceptance: run `FavoriteStickersDeviceTest` with `-e favorites_protocol true`. It uses production rules and local saved login, requests two items per page to exercise real cursors, and checks animated/static publication, readback, URI reuse, deletion/resave and readable sharing URIs in an isolated store. The old test-only protocol recipe was removed when its fields moved into the signed production bundle.

Account-work fixtures: `AccountWorksDeviceTest` covers separate tab selections, stable previews, batch task restoration, partial failure/retry and verified publication reuse in an isolated store. Its `account_works_live=true` case checks two real pages of each collection and emits only sanitized counts. Batch snapshots contain actual work references; each work uses `PostDownloader` and its own manifest under the batch task directory. Batch output keys are existing `StoredAsset.key` values so multi-work sharing can resolve each work's verified file. Single-work choices create ordinary POST-mode download tasks.

Production comment acceptance: `NativeProtocolDeviceTest` with `-e native_protocol true` uses the active signed rules, actual comment/reply cursors, multi-image originals, live-photo motion and static stickers. It publishes into an isolated store, verifies system readback hashes/reuse and deletes only its new outputs. `comment-originals-case.json` contains sample identifiers, not an independent transport recipe.

On MIUI, test installation can show a short-lived USB confirmation even with `adb install -r -t`; choose Continue for `com.emojetools.app.test`. A timeout returns INSTALL_FAILED_USER_RESTRICTED. Stop the mobile MCP device server before instrumentation: both claim UiAutomation. Resume MCP after the test process exits.

Use the configured local `mobile-mcp` for UI navigation and screenshots; its launcher is `F:\Tools\MobileMCP\mobile-mcp.cmd` and its usage notes are `F:\Tools\MobileMCP\README.md`. This shared path avoids Windows packaged-app AppData redirection and is visible to Explorer and other agents. ADB remains available for package/build/instrumentation operations. Always discover the connected device first.
