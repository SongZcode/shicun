# Favorite Stickers Acceptance

2026-10-20, Asia/Shanghai. Current source/APK `0.10.0-comment-originals` (21), signed embedded bundle 16. Xiaomi Mi 9, Android 11/API30, physical 1080x2340 at 440dpi. Initial UI screenshots below were captured on bundle 14; current production-rule device acceptance uses bundle 16.

## Observed

- Formal Douyin entry opens the account collection without a work link or search input. The saved WebView session made ordinary signed HTTP requests: self HTTP200 and favorites HTTP200; the current sample had 19 unique favorites and server completion in one page. No cookie values were exported, no browser API execution was used.
- Actual thumbnails loaded. Opening a GIF played motion: two phone screenshots differed at 9,724 sampled pixels inside the image region. Static WebP and animated GIF retained their actual file types when prepared for sharing.
- Checked an animated and a static item, opened/closed preview, and recreated the activity through display-size changes. Both selections remained checked. A 320dp-wide viewport used two columns; normal width used three. Bottom actions stayed visible.
- A single GIF and a GIF/WebP pair each completed their existing foreground output task and opened the system share sheet. Both share sheets were cancelled; nothing was sent to another app or person. The gallery was not used for this UI pass.
- The existing login and system media were preserved. Temporary display-size override was reset to the original physical configuration.

## Artifacts

- Normal collection (local evidence)
- Animated preview (local evidence)
- 320dp collection (local evidence)
- Two-file system sharing (local evidence)
- APK: `app/build/outputs/apk/debug/app-debug.apk`.
- Current APK hash and final checks: [comment/stability acceptance](comment-originals.md). `tools/build.ps1 -Check` passes 94 JVM tests and 9 signing tests, lint 0 errors / 11 warnings. Bundle 16 signature, ZIP/file-list and file hashes passed existing-key packaging verification.

## Current Device Acceptance

The MIUI USB confirmation was accepted and the test APK installed. `FavoriteCollectionDeviceTest` now runs against isolated data: paging/retry retains checked items and visible thumbnail instances/drawables, session changes clear selection, login/empty/error actions differ, task snapshots retain real collection identity, system saving/reuse/deletion/resave and readable temporary sharing pass. Interrupted batch/store reopening retries only the missing item and retains the first system URI.

Live `FavoriteStickersDeviceTest` exercised 10 pages with page size 2: cursors 0, 2, ..., 18, 19; 19 unique items to server end, matching the ordinary full-page item set. A 320x320 animated GIF (2,376,290 bytes) and a 232x504 static WebP (11,262 bytes, declared JPEG) passed actual decoding, gallery publication, SHA-1/byte readback, repeated-save URI reuse, deletion/resave, readable share URIs and read-grant flags. Tests removed their own new publications. See sanitized live result (local evidence).

Pending: fresh login, a real second account, an actual process kill during a live Favorite binary transfer, recipient-app consumption, API26-28 and other devices. A share intent/read grant check does not prove a particular third-party app accepts every format; choosers were cancelled without sending.
