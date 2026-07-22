# Legado-X Repository Rules

## Remote Boundary

- `origin` is `https://github.com/XziXmn/legado-X.git` and is the default push target.
- `upstream` is `https://github.com/Luoyacheng/legado-E.git` and is fetch-only.
- The `codex/legadohub-reader` branch contains product-specific reader capabilities. Do not push it to upstream or open an upstream pull request unless the user explicitly requests that exact action.
- Upstream features may be fetched and merged, rebased, or cherry-picked into this branch. Resolve conflicts without dropping the chapter-comment protocol, source-scoped security boundary, or package id `io.legadox.app`.
- Never force-push upstream. Normal pushes also default to `origin`.

## Build Boundary

- Releases follow upstream Legado-E: sign with the repository public test key (`.github/workflows/legado.jks`). This is intentional for an open-source distribution model.
- Do not invent a private production keystore unless the user explicitly requests it.
- Never commit private keystores or their passwords.
- Keep generated APKs and local build output out of Git.
- Push only to `origin`. Never push to `upstream` unless the user explicitly requests that exact action.
