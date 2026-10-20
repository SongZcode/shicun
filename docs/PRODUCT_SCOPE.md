# Product Scope

Current agreement, 2026-10-20. Implementation status belongs in [STATUS.md](STATUS.md).

## Experience

- Android, ordinary phone, entirely on device. No backend, manual cookie export or dependency on the installed official app.
- Organize by platform channel: Douyin and Bilibili. Video and image-based works share one work entry. Present a phone app, not a diagnostic parameter panel.
- Favorite Stickers is the next priority: log in, browse actual thumbnails, preview/select, Save or Share. No work link, posting a comment or sticker search.
- Saved Works and Likes feed the existing work downloader: separate tabs with actual covers, response-cursor paging, preview and retained selections, single-work options and batch Save/Share. No new public work IDs or manual links are required. History/social/account audits do not automatically become product features.
- Open the official phone/code login form at phone size; persist success and preserve the interrupted task. QR login/saving was removed by agreement.

## Saving And Media

- Buttons say Save and Share. Save images/motion/video directly to the system gallery; audio to system audio. No mandatory library-then-export step or second permanent app copy.
- Keep an internal index for verification/reuse. Reuse a valid saved system file; detect deletion and allow saving again. Share via readable temporary/system URIs with appropriate permissions.
- Pausing retains the task, selection and completed outputs. Continuing reuses verified complete files and restarts an unfinished file; do not describe this as byte-range resume. Show that boundary on interrupted download tasks.
- Download the platform-provided resource without re-encoding. The platform may already have compressed it. Do not promise the uploader's uncompressed original.
- Check real format/decodability, not only URL extensions or declared dimensions. Preserve motion and all returned comment images. Prefer compatible JPEG originals over HEIF/AVIF mirrors for static content; keep animated mirror order.
- Complete video soundtrack and separately provided background music are distinct choices, including audio-only saving. No voice/music separation or promise of a complete original song.
- Account-work batches save the highest available video or the full image set, without adding separate soundtrack/music/cover files by default. Opening one work retains the existing rendition, image, complete-audio and background-music choices.
- Audio lists/player use the source work cover. Conversion, when implemented, creates separate outputs and does not overwrite originals; WeChat/video-to-GIF needs its own scoped acceptance.
- Sharing WebP/HEIC/AVIF offers Original or Compatible Images. Compatible sharing creates temporary JPEG for opaque static images or PNG for transparency, preserving originals and their dimensions. Animated images and videos remain unchanged. This is not a general converter or a guarantee of acceptance as a recipient app's animated sticker.

## Bilibili

- BV/av/video links and b23.tv shortlinks, actual cover/title, single-part selection, available video qualities, complete audio and optional cover. Reuse Save/Share, foreground tasks and verified-file reuse.
- Add official WebView login, encrypted Bilibili sessions isolated from Douyin, server verification before saving login, and refresh of the current work's available qualities after login. Expose only qualities actually returned for the account/source; login does not grant VIP access.
- Accept ep/ss and official bangumi/play links, including movies using that flow. Show actual episode titles and available compatible qualities. Resolve real aid/cid/episode references when switching episodes. Refuse preview-only or DRM-protected resources rather than saving them as full content. Respect the logged-in account's returned entitlements; separately paid and region-restricted titles are not promised.
- Align account browsing with Douyin: favorite-folder selector, favorite works and Recent Likes tabs, retained selections, per-account/platform/folder caches, work options and batch Save/Share. Bilibili's recent-like API is a bounded list, not full historical likes; do not offer fictitious pagination.
- Fetch only cover/title/author/duration summaries while browsing. Display each page immediately, preserving visible items and selections during later requests/retries. Fetch stream details only when opening or downloading a work; do not wait for the complete collection or fetch every video's media in advance. Keep the existing page-at-a-time Douyin behavior.
- Download media directly from Bilibili CDN. Combine compatible AVC/AAC DASH tracks locally without re-encoding. Bilibili has a separate configurable 8GiB per-track limit and long request duration with a short idle timeout. Batches select each work's default first part/highest compatible quality; other parts/episodes are selected through work options. No Bilibili comment flow or whole-season/all-part batch is added.
- Platform/part references, cache and system output paths stay isolated from Douyin. Platform paths, response mappings and WBI signing remain hot-updatable JS/JSON.
- Consult existing permissively licensed GitHub implementations. Acquire GitHub source and dependencies through domestic mirrors/acceleration with pinned-version/hash verification; do not route video through those mirrors or start interface reverse engineering.

## Comment Lookup

- Public modes: Comment Content Search, Commenter Search, My Comments. My Comments means this logged-in account within the supplied work, not global comment history.
- Content/nickname fragments support normalized fuzzy matching. Complete public Douyin handles use exact normalized matching, accepting plain handles or copied prefixes, case/width/invisible-character variants while preserving meaningful punctuation.
- Recognize copied `@nickname: comment text` in either search input and match both parts. Keep the clipboard action; hide it and the target field for My Comments. Internal author/comment IDs are not public search modes.
- Display matches progressively and preserve selections/thumbnails. Fuzzy matches never stop automatically. Stop is a consistent bottom action; users may stop and save already found assets.
- The comment material list displays matched comments with available media; text-only comments remain in the scan/cache. Arrange a comment's images/motion in a compact grid. Tapping opens a large image/animation/video preview; checkboxes select independently and preview selection stays synchronized.
- Comment output shows success/failure counts. Retrying retains valid successful outputs and only processes failed or missing files; the final summary counts the entire selection.
- Top-Level First / Replies First changes traversal order. Replies First still requires discovering parents; neither ranking nor full comment-section coverage is promised.
- Reuse recent same-work cache within the same login session. A changed query appends new matches to previous results and preserves checked items/previews; it creates no duplicate task and does not reset scanning.
- Continue an unfinished scan from its checkpoint. Completed/capped scans do not expose Continue. Do not restore the removed Refresh action.
- Keep the last 20 task records; auto-clean older history and allow manual deletion, including stopping an executing task. This does not delete saved system media or its index.
- Use real task/result thumbnails without flashing between placeholders and loaded images. Surface progress, partial results, typed login/errors and recoverable failures; an anti-bot empty body is never a successful empty list.

## Development Boundaries

- Keep the app compact. No compatibility/migration layer for obsolete unreleased versions. Current cache, pause/continue, supported Android APIs and bundle rollback still matter.
- Comment-command import is deferred; its feasibility remains undetermined. Research is sealed until the user explicitly reopens it.
- Ordinary HTTP/native-compatible endpoints are acceptable only when they work independently with local, hot-updatable rules. No Root, Frida, copied official state or vendor SDK in the product.
- Favorite Stickers, Saved Works/Likes, Bilibili login/downloading and lightweight static-image sharing are implemented. Current additions are Bilibili PGC and favorite/recent-like browsing with incremental summaries. Broad conversion, social/account audits and other catalog expansion remain deferred.
