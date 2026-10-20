# Third Party Notices

Bilibili WBI signing, DASH/PGC extraction and favorite-resource access follow yt-dlp (Unlicense), pinned commit `897341241f2bfb2439fe0367f4c7c9ccd29a3828`, `yt_dlp/extractor/bilibili.py`. The implementation is adapted to the existing local JS/JSON adapter and Android MediaMuxer; no yt-dlp runtime or FFmpeg binary is bundled. Source/license were acquired through the domestic acceleration endpoint `ghfast.top` and checked against GitHub Git blob IDs. License: `licenses/yt-dlp-Unlicense.txt`.

Bilibili favorite-folder and recent-like endpoint/parameter facts were cross-checked against `nooblong/bilibili-api` commit `4789508a93f3a39b7d3c896273309db892a9cc8b` (`favorite-list.json`, Git blob `6e6b0fcb10b4377c6cab396b746dd3538268cb43`) and `DIYgod/RSSHub` commit `5b399b2fdd77d006973f73959f8f24b2bc117d5f` (`like.ts`, Git blob `e930c4d1c87d3905848700fdf5aefd67b26f9657`). Reference files were acquired through `ghfast.top` and blob-verified. These repositories use GPL/AGPL; no implementation, SDK, runtime or file from them is copied into the product. The native list transport/state/parser integration is independently implemented using protocol facts and the existing application components.

The a_bogus port in tools/sign/douyin.entry.js follows jiji262/douyin-downloader, MIT, copyright (c) 2026 jiji262. Its upstream file credits JohnserfSeed, Apache-2.0. Both notices are retained in licenses/.

The secsdk implementation follows Evil0ctal/Douyin_TikTok_Download_API, Apache-2.0. The implementation preserves parameter order and uses the exact bytes sent.

SM3 uses sm-crypto 0.5.7, MIT. MD5 uses js-md5 0.8.3, MIT. esbuild is a development-only tool, MIT.

Interface icons use lucide-static 0.468.0, ISC, mechanically converted from upstream SVG assets to Android vector drawables. The license is retained in licenses/lucide-ISC.txt.

Android dependencies: Kotlin and kotlinx.coroutines (Apache-2.0), AndroidX Core and WebKit (Apache-2.0), OkHttp/Okio (Apache-2.0), Bouncy Castle (MIT). JUnit (EPL-1.0), JSON-java (public domain), OkHttp MockWebServer (Apache-2.0), and AndroidX Test (Apache-2.0) are used only in tests.

No code from JoeanAmier/TikTokDownloader, GPL-3.0 or AGPL-3.0 repositories is included.
