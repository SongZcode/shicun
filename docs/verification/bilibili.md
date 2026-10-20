# Bilibili Acceptance

2026-10-20, Mi 9 / Android 11 / API30. App `0.12.0-bilibili` (23), signed bundle 18 / minApp23.

- `tools/build.ps1 -Check`: 94 JVM tests, 10 signing tests, normal/test APKs; lint 0 errors / 14 warnings.
- `BilibiliDeviceTest#muxPreservesCompressedSamplesAndPlatformStorage`: passed. Merged fixture tracks retain every compressed sample (SHA-256 and packet counts); numeric Douyin and real Bilibili aid/cid storage/previews remain separate. Existing Douyin directory naming is preserved.
- `#publicVideoPartsDownloadSaveReuseAndShare`, `bilibili_live=true`: passed. Production JS/JSON and native HTTP resolved a four-part public sample, av identity and P4; switching to P1 updated the actual cid. Public DASH sample `BV1bK411W797` exposed two video qualities. Its 360P AVC video, AAC audio and cover saved successfully. System readback matched SHA-1, video had both tracks, repeated saving reused the same three URIs, and sharing provided readable URIs/read grants. Log: `public_parts=4 variants=2 saved=3 video_audio_tracks=true hash_readback=true uri_reuse=true share_read_grant=true part_switch=true`.
- Initial mobile-UA CDN requests returned 403; standard desktop-browser UA succeeded. The fix is in Bilibili JSON. No runtime SDK/backend/browser API execution or new dependency was added.
- Production UI resolved legacy single-file `BV1nx411u79K`, actual covers/titles, 480P/360P options and P4/P1 switching. Normal and 320dp layouts were inspected; density was restored. Options (local evidence), parts (local evidence), 320dp (local evidence).
- Code 24 / bundle 20 subsequently passed authenticated 720P/qn64 download, both tracks, isolated system publication, SHA-1 readback and decoded frame after the user's official login. The existing production work refreshed to 720P/480P/360P. See [login acceptance](login-share.md) and actual qualities (local evidence).
- Tests used isolated databases/directories and removed only their own new MediaStore rows. Production login and existing media were preserved; no app data reset. Chooser delivery to another app was not exercised.

Reference: yt-dlp commit `897341241f2bfb2439fe0367f4c7c9ccd29a3828`, acquired through `ghfast.top`; extractor Git blob `ca37b79deea868e0fb03698de6f1b28caaca3573`, Unlicense Git blob `68a49daad8ff7e35068f2b7a97d643aab440eaec`. No third-party runtime/binary is bundled.

Original code-23 APK SHA-256: `fa79b18e8495967eb8f632988d76904d9b4f1c7fee4c7c6eb0f2872199eddae4`.

Account access never bypasses platform permissions. Current scope and remaining acceptance are listed below.

## PGC And Collections

2026-10-20, Mi 9 / API30, app `0.14.0-bilibili-library` / code 25, signed bundle 23 / minApp25. PGC and collections are production adapter/UI features, using the existing encrypted login, downloader, mux, publisher and sharing. The obsolete separate PGC probe/recipe was removed.

- `tools/build.ps1 -Check`: 97 JVM / 10 signing tests, both APKs, lint 0 errors / 13 warnings. Added fixture coverage for platform/account/folder isolation, sparse-reference serialization, repeated-page stop and downloads outliving the idle timeout while data continues arriving. Existing-key bundle signature/archive/file verification passed.
- `BilibiliLibraryDeviceTest#realCollectionsAndWholeMemberEpisode`, `bilibili_library=true`: passed in 47.266s on the initial production integration. Native production rules resolved `b23.tv/ep718242`, ep/ss inputs and actual episode identity changes. An independent anonymous adapter was rejected as NEED_LOGIN for the member preview. The account returned whole, non-preview, non-DRM media; full 360P download/mux/publication/readback passed SHA-1 and decoded a frame near the end. Output: `full_duration_ms=1450048 bytes=68572818 saved=1 uri_reuse=true share_grant=true end_frame=true`.
- `collections_only=true` on bundle 23, plus the shared UI selection/thumbnail/paging/retry test: two cases passed in 11.863s. Real favorite folder count 1; two-item pages returned four unique works after two pages, with more available and selection retained. Recent Likes returned 20 works in one bounded list. A real five-second collection work passed sparse-ref resolution, batch system saving and URI reuse. Production ordinary-page browsing returned all 14 favorites. Raw detail/playback requests are deferred until opening/saving; list records contain only summaries.
- The initial API check sent page 1/2 to Recent Likes and got the same 20 records. Product rules therefore removed ignored paging parameters and the page labels it Recent Likes, with no fake historical paging. Favorite pagination uses the real page-number API and `has_more`.
- Production UI shows actual favorite/recent-like covers, independent selection across tabs and work options. Initial transient cover failures recovered after reopening cached files; cover rendering now retries once independently and failed tiles can retry on rebind. Final shared collection selection/thumbnail/paging/retry case passed again in 1.622s. Normal and 320dp layouts were inspected, density restored to 440. The user's formerly failed episode-1 task now opens actual title/cover, episode menu and 4K quality through the formal page, without creating another download.
- Prior bounded member checks: user-supplied episode `508404` is free; member-labelled `718242` was preview-only anonymously and whole after VIP login. Both returned 2160P/AVC qn120 to this account. At most 4MiB video + 1MiB AAC per sample passed five-second native mux/frame decode with no DRM initialization/encrypted samples. A preview also reported full-length `timelength`; production checks playback state/preview flags instead of trusting duration.
- All saved test outputs used isolated databases/directories; only new test-owned MediaStore URIs were removed. Both production logins, account collections and existing gallery media are preserved. Choosers were not sent to recipients. No new runtime/dependency, backend, browser API execution or DRM key handling was introduced.

Favorites (local evidence), recent likes (local evidence), PGC options (local evidence), episodes (local evidence), 320dp (local evidence).

Code-25 APK SHA-256: `12ac1fafa1bcdc185f17922295f5477c481c74c12ab556c5c0f495bb647dfd33`; superseded by the fixed closeout build below.

Not verified: movie-specific titles, full 4K/multi-GB downloads, long CDN expiry, real second-account/multiple-folder switching, recipient consumption and other Android/OEMs. The account only had one folder; folder/session isolation has fixture coverage. PGC movie links share the implemented episode/season path, but the accepted full sample was a member anime episode. No followed-series library, whole-season/all-part batch or Bilibili comment flow is claimed. Batch work saving defaults to each work's first part/highest compatible quality.

## Closeout

2026-10-20, Mi 9 / API30, app `0.14.1` / code 26, unchanged signed bundle 23 / minApp25.

- `tools/build.ps1 -Check` passed: 97 JVM tests, 10 signing tests, both APKs built, lint 0 errors / 13 warnings. Subsequent test-harness-only changes rebuilt the test APK.
- `BilibiliLibraryDeviceTest#longMemberDownloadPausesAndContinuesInBackground`, `bilibili_closeout=true`: passed in 53.984s after the user restored the phone network. Prior network attempts timed out before download; they are not successful download evidence. The harness foregrounds the app via shell launch because MIUI blocked background ActivityScenario launch.
- Real member episode 718242 downloaded in `TaskService` after returning Home. The test observed a new partial file over 1MiB and the foreground notification, turned the screen off, paused, verified persisted selection/interrupted state and partial-file cleanup, then continued to full publication while the screen remained off. Full output was 1,450,048ms / 68,572,818 bytes with audio/video tracks, matching SHA-1 readback and a decoded end frame. Sanitized result: `background_service=true screen_off=true paused_partial_clean=true selection_retained=true continued=true hash_readback=true end_frame=true`.
- Only the isolated fixture task and its new publication were removed. Both production logins, task history and existing media remain intact. The test restored the screen. This verifies a short screen-off interval; prolonged Doze, process death during live binary IO and other OEMs remain unverified. Continuing restarts the unfinished file; it does not resume at a byte offset.
- Production UI now labels batch first-P/highest-compatible-quality behavior and recent likes as a bounded list. Normal and 320dp wording/buttons were inspected without overlap; density restored to physical 440. Favorites (local evidence), recent likes (local evidence), 320dp (local evidence), actual foreground notification (local evidence).

Fixed APK: EmojeTools-0.14.1-test.apk (local evidence), SHA-256 `672e8c829137c2adb3d77a99d5c7419365396c4b74d935d656674ee95f951328`. Installed production `base.apk` matches. This is the runnable app; the separate instrumentation APK is not needed for ordinary usage.
