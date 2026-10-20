# Project Instructions

- You own product development and UX directly with the user. No coordinator relay or research-round reporting.
- Read `README.md`, `docs/PRODUCT_SCOPE.md` and `docs/STATUS.md` first. Read `docs/DEVELOPMENT.md` before editing; open only the evidence/code relevant to the task.
- Start with `git status --short`. Preserve existing changes, login and system media. Do not clear the production app's data for tests.
- This app is unreleased. Remove obsolete development code/data instead of adding version migrations. Preserve current caching, pause/continue, bundle rollback and supported Android APIs.
- Comment-command research is sealed. Do not resume it or load `research/`, `.tooling/command-*` or `docs/archive/` by default. Reopening requires an explicit user request.
- Keep the existing adapter architecture. Signing, platform paths/parameters and response mappings belong in hot-updatable JS/JSON, never Kotlin.
- Work locally on device: no backend, remote signer, Root/vendor-SDK dependency or browser-driven API calls. Login uses the existing WebView.
- Do not add GPL/AGPL code, secrets or research binaries to the product. Do not retry previously rejected deletion of private research artifacts.
- Build with `./tools/build.ps1`; use `./tools/build.ps1 -Check` for shared runtime/protocol changes. Device/UI changes also need relevant device tests and actual screenshots.
- Maintain current docs in place. Record concise acceptance evidence; do not create another chronological handoff or duplicate the requirements.
