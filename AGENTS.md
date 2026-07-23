# Legado-X Repository Rules

## Remote Boundary

- `origin` is `https://github.com/XziXmn/legado-X.git` and is the default push target.
- `upstream` is `https://github.com/Luoyacheng/legado-E.git` and is fetch-only.
- The `codex/legadohub-reader` branch contains product-specific reader capabilities. Do not push it to upstream or open an upstream pull request unless the user explicitly requests that exact action.
- Upstream features may be fetched and merged, rebased, or cherry-picked into this branch. Resolve conflicts without dropping the chapter-comment protocol, source-scoped security boundary, or package ids: release `io.legado.app.release`, beta `io.legado.app.beta`, debug `io.legado.app.debug`.
- Never force-push upstream. Normal pushes also default to `origin`.

## Build Boundary

- Releases follow upstream Legado-E: sign with the repository public test key (`.github/workflows/legado.jks`). This is intentional for an open-source distribution model.
- Do not invent a private production keystore unless the user explicitly requests it.
- Never commit private keystores or their passwords.
- Keep generated APKs and local build output out of Git.
- Push only to `origin`. Never push to `upstream` unless the user explicitly requests that exact action.

## Release Channel

- **Default = beta (测试版).** Feature work, UX experiments (e.g. in-reader comment panel), and routine fixes ship as `io.legado.app.beta` / tag `beta` unless the user **explicitly** asks for a formal release.
- CI formal path is triggered only when `app/src/main/assets/updateLog.md` changes in the pushed commit. Do **not** edit `updateLog.md` for beta work.
- **Formal release** (`io.legado.app.release` + version tag): only when the user clearly requests 正式发版 / 正式推送 / formal ship. Then update user-facing `updateLog.md` and push.
- Never treat “push / 构建 / 发版” alone as formal; require explicit formal intent.
