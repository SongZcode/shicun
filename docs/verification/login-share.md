# Login And Compatible Sharing

2026-10-20, Mi 9 / Android 11 / API30. App `0.13.0-login-share` (24), signed bundle 20 / minApp24.

- `tools/build.ps1 -Check`: 94 JVM / 10 signing tests, normal/test APKs, lint 0 errors / 13 warnings. Final share wording and mobile WebView profile rebuilt separately; bundle 20 signature/archive/files verified with the existing key.
- `LoginShareDeviceTest`: two cases passed. Reserved fixture domains/separate encrypted namespaces verified reload, UA mismatch rejection and logout isolation. No production cookies/login were cleared.
- Native fixtures verified opaque WebP -> JPEG, transparent WebP -> PNG with original dimensions/transparent pixels, real two-frame WebP and MP4 preservation, unchanged original hashes, readable mixed sharing URI grants and failed-conversion cleanup. The 32x24 animated fixture is locally generated, not downloaded third-party media.
- Production stored-file UI converted a real static WebP into an `image-*.jpg` readable sharing URI. The chooser was cancelled without sending. HEIC image-sequence material was preserved. Temporary conversions live only under the existing 24-hour staging cache, with no gallery or index publication.
- Official Bilibili H5 phone/password form rendered at phone width with the mobile `login_browser` profile; API/CDN retain the desktop UA. Login must be server-verified before encrypted persistence. No browser-driven API calls, credentials in fixtures/logs, backend, SDK or new product dependency was added.
- After the user completed official login, `BilibiliDeviceTest#authenticatedAccountGetsAvailableHighQuality` passed with `bilibili_authenticated=true`. Native API confirmed authentication; sample `BV1bK411W797` returned 720P/qn64, downloaded and published with both video/audio tracks, matched system readback SHA-1 and decoded an actual frame. Sanitized log: `authenticated=true height=720 quality=64 saved=1 frame_decoded=true`. Only its new gallery output was removed; existing image row 658 and both saved platform sessions remained.
- Production UI cold launch retained Bilibili login. Updating the existing work refreshed the old 480P/360P list to 720P/480P/360P. This check reused the existing task and did not create another production download.

Official login (local evidence), compatible sharing (local evidence), authenticated qualities (local evidence).

APK: `app/build/outputs/apk/debug/app-debug.apk`, SHA-256 `6c353ced74611a043f22f5d5a2432725e3d8db2c363d48387100cfe12c0cfeee`. Final shared login wording was rebuilt without repeating device tests.

Pending: external recipient consumption; static HEIC/AVIF and older Android/OEM coverage. Subsequent 4K samples and whole member-episode acceptance are recorded in [Bilibili acceptance](bilibili.md#pgc-and-collections); full 4K remains unverified. The user completed the official login challenge manually; no phone number/code was added to project files. Login does not grant VIP rights; only returned compatible qualities are selectable.
