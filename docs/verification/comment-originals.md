# Comment Originals And Download Stability

2026-10-20, Asia/Shanghai. Current app `0.11.0-comment-experience` (22), signed bundle 16 / minimum app 21. Original-protocol acceptance below used code 21; the material-page UX acceptance uses code 22. Xiaomi Mi 9, Android 11/API30, 1080x2340 / 440dpi. My Comments and the Saved/Liked layout are retained.

## Material Page UX

- Text-only matches stay in scanning/cache but are omitted from the material list. Multiple assets from one comment use square thumbnail grid tracks: three columns normally, two at 320dp. The original two-image live comment was inspected on both widths without overlap; the density override was reset.
- Tapping a thumbnail opens a full-screen preview with previous/next, retry and a synchronized selection checkbox. Images use the existing decoder with a larger display bound; videos use local VideoView playback and loop until dismissed. Closing stops the player/animation. Real image preview and selection synchronization were observed on the production page; PNG/MP4 loading, playback and dismissal passed the focused device case. A new live-comment GIF sample was not separately exercised; its decoder is reused from earlier animated Favorite acceptance.
- Comment retries retain verified successful output URIs and process failed/missing items. The final success/failure summary counts the original selection. Partial-result actions say Retry Failed Items. Isolated Save/Share and partial retry checks passed without touching production media.
- Only four targeted device cases ran for this UX change: filtering/grid/large-video preview, progressive thumbnail/selection retention, comment retry totals/URI retention and task Save/Share behavior. All passed. `tools/build.ps1 -Check` passed; final wording was rebuilt without repeating the device suite. Lint has 0 errors / 14 warnings, including three unused former text-only result strings.
- Actual screenshots: compact grid (local evidence), large preview (local evidence), 320dp grid (local evidence).
- Current APK SHA-256: `065b1ad6a1f59911f8c6ca11c8723a21e8f0d9496341cb8a246f8dbe65c0197b`, identical to installed `base.apk`. The earlier hash below describes the code-21 protocol acceptance artifact.

## Delivered Behavior

- Comment/reply requests use the production anonymous native profile. Paths, origin, UA, query, form and field mappings remain in signed JS/JSON. A locally generated persistent identity is separate from login; comment requests never read/send account cookies or UIFID. Account collections retain the saved WebView session.
- Multi-image comments keep all returned original images. Live-photo motion remains a separate video choice. Static stickers are marked from their mapped source fields and prefer a provided JPEG over HEIF/AVIF mirrors; animated/unknown sticker mirror order is retained. A URL containing `.gif` is not proof of animation.
- Pausing closes the HTTP call during body reads, including a stalled stream. Cancellation and disconnected responses remove partial files. Existing verification, gallery reuse, deletion/resave, foreground tasks, continuation and rollback remain in place.

## Production UI Evidence

Work `7692474777461637477`, two-image comment `7695928855463150376`. The owned task fetched 12 top-level pages, 586 row appearances / 434 unique comments / 152 duplicates, then continued replies. These are observations, not complete-comment-section coverage.

- Both original thumbnails displayed and stayed checked through pause/continue, backgrounding, force-stop and cold reopening. Reply continuation advanced the parent checkpoint while top-level pages stayed at 12.
- Sharing prepared two JPEGs and opened the system chooser. Nothing was sent. Their bytes/SHA-1 matched the independently observed platform resources:

| Bytes | SHA-1 |
| --- | --- |
| 190752 | `02cd6578721ac9390e378d829a39a7143953f9b2` |
| 175008 | `846059bc1b7b04d140cbcd43b30a69cc000738c5` |

- Two originals selected (local evidence), retained selection (local evidence), cold recovery (local evidence), two-file sharing (local evidence).
- Static sticker sharing (local evidence) was an HEIC sample before the JPEG preference fix. It is not animated-GIF evidence.

## Validation Boundaries

`tools/build.ps1 -Check`: 94 JVM tests and 9 signing tests passed; lint 0 errors / 11 warnings. Tests include stalled-body cancellation, incomplete-body retry, anonymous/session isolation, unchanged web signing vectors, source-based static/animated behavior and mirror ordering.

The final device run discovered 73 cases: 70 passed and 3 were conditionally skipped (two older live sample cases without their separate opt-ins, plus external recovery without its phase). The latter passed in a separate seed / force-stop / verify run. Current live opt-ins for comments, Favorite Stickers and Saved/Liked Works all passed. Collection fixtures cover visible thumbnail/drawable retention, injected retry, isolated session selection changes, mixed-media saving and interrupted batch/store reopening.

The production comment sample saved four byte-identical system copies: the two JPEGs above, a 526,674-byte live-motion MP4 (`aee9ddd938ba9752d761188cb7c9072a3a881cdc`) and a 1,008,099-byte HEIC sticker. The MP4 decoded a real frame; it does not require an audio track. The sticker arrived via the platform's animated-source field, which alone does not prove animated bytes. See sanitized live result (local evidence). The separately tested Favorite GIF decoded as an actual animated drawable.

APK: `app/build/outputs/apk/debug/app-debug.apk`. SHA-256 `0c8460a3b6cc6334cbdb3c07ce16258090c83bd8ce60be97bf7ef778cf07bfae`, identical to the installed Mi 9 `base.apk`. Only bundle 16 and its manifest are embedded. `git diff --check` passed. Test-owned publications were removed; the existing production MediaStore row was retained.

Fresh phone/code login, a real second account, API26-28 and other OEM/Android versions remain unverified. Deterministic fixture interruption/store reopening and a process-kill check are distinct from a live process kill during a large binary transfer. Platform-provided originals can already be compressed. No full-account or complete-comment coverage is claimed.
