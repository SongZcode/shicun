# Saved And Liked Works Acceptance

2026-10-20, Asia/Shanghai. Current APK `0.10.0-comment-originals` (21), signed bundle 16 / minimum app 21. Local Xiaomi Mi 9, Android 11/API30, 1080x2340 / 440dpi. Initial UI screenshots below were captured on bundle 15; the 320dp override was reset. Current production-rule device acceptance uses bundle 16. The layout remains unchanged.

## Implemented

- Existing saved WebView login, account identity, separate saved/liked tabs, actual covers, titles, authors, durations/image counts, checked-item retention, retry and independent persisted caches. No search or manual work ID entry.
- Saved works use configured POST/form requests; liked works use configured GET. Paths, parameters, headers and response mappings remain in signed JSON/JS. Signed query bytes are unchanged; body reading and decoding run on IO, including observed responses larger than 1 MB.
- Batch defaults: highest available video or all returned work images. A single work opens the existing rendition/image/complete-audio/music/cover selection. Existing foreground service, downloader, verification index, gallery publisher and URI sharing are reused.
- Batch snapshots retain actual work IDs and account-derived collection keys. Per-work media/manifests support cached retry; comment IDs, comment ledgers and invented work IDs are not used.

## Observed On Phone

| Check | Observation |
| --- | --- |
| Saved pagination | Two response-driven pages: 17 then 30 usable works, 3 then 4 unavailable, server end. Repeated after the restoration fix. |
| Liked pagination | Two response-driven pages: 17 then 35 unique works; more available. Repeated after the fix. Counts are session samples, not full-account guarantees. |
| Selection/cache | Separate saved/liked selections survived page loads, tab changes, APK replacement and normal/320dp recreation. Returning to a scrolled tab restored the visible row. Expired caches refetched while retaining checked works. |
| Single work | Actual video cover/options and an image-work preview/image checkbox loaded through existing detail parsing. |
| Downloads/sharing | One saved video, two saved videos, one liked video, and a two-video/one-image saved batch completed and opened system sharing. Every chooser was cancelled; no recipient/app was selected. |
| Restoration fix | Touch-mode reopening/recreation originally crashed in GridView `fillSpecific` with adapter position -1. Replacing inherited `setSelectionFromTop` with clamped `setSelection` fixed observed re-entry, tab restoration and density recreation. The same call was corrected in Favorite Stickers. No new app fatal exception was observed during the subsequent checks. |
| Media protection | No app-data reset or login removal. Automation accidentally selected Save once, publishing one 3,994,488-byte video. Its exact new MediaStore row was verified by app ownership and creation time, then removed; a follow-up query returned no row. Subsequent sharing recovered the missing file. No pre-existing gallery item was deleted. Shell URI readback was denied, so no readback hash success is claimed. |

## Build And Artifacts

- `tools/build.ps1 -Check`: 94 JVM tests / 9 signing tests passed; lint 0 errors / 11 warnings; normal/test APKs build. Coverage includes configured GET/POST transport, cursor mappings, malformed/empty lists, failure/retry, deduplication, caps, session isolation, batch defaults and body cancellation.
- Bundle 16 passed existing-key Ed25519, archive/exact file-list and per-file verification. Only `assets/bundle/bundle_v16.zip` and its manifest are embedded.
- APK: `app/build/outputs/apk/debug/app-debug.apk`.
- Current APK hash and final checks: [comment/stability acceptance](comment-originals.md).
- Screenshots: saved list (local evidence), liked list (local evidence), 320dp list (local evidence), video options (local evidence), image options (local evidence), two-video sharing (local evidence), liked video sharing (local evidence), mixed sharing (local evidence).
- Final bundle 16 production UI: saved grid (local evidence), liked grid (local evidence). Actual covers loaded; Save/Share remain visible and disabled until selection.

## Current Device Acceptance

The test APK is installed. `AccountWorksDeviceTest` exercises tab/visible-thumbnail retention, batch task restoration, mixed two-image/one-video publication, partial retry, verified-file/system-URI reuse, deletion/resave and readable sharing URI/grant checks in an isolated store. A second interruption case reopens its database/manifests, keeps the first published URI, reuses cached work metadata and downloads only the missing second image.

Live collection requests again advanced two actual response cursors per tab: saved 17 + 13 usable / 3 + 1 unavailable to server end; liked 17 + 18 usable, more available. See sanitized live result (local evidence).

Pending: an actual process kill during a large live batch transfer, live multi-image work batch output, fresh login, a real second account, API26-28/other-OEM checks and recipient-app consumption. Fixture multi-image output does not prove live work motion support. The isolated gallery checks delete only their new rows; production login and pre-existing media remain intact.
