# Legado-X Repository Rules

## Remote Boundary

- `origin` is `https://github.com/XziXmn/legado-X.git` and is the default push target.
- `upstream` is `https://github.com/Luoyacheng/legado-E.git` and is fetch-only.
- The `codex/legadohub-reader` branch contains product-specific reader capabilities. Do not push it to upstream or open an upstream pull request unless the user explicitly requests that exact action.
- Upstream features may be fetched and merged, rebased, or cherry-picked into this branch. Resolve conflicts without dropping the custom package identity, chapter-comment protocol, or source-scoped security boundary.
- Never force-push upstream. Normal pushes also default to `origin`.

## Build Boundary

- Remote test artifacts are Debug APKs signed with the repository's public test key. They are not production releases.
- Production releases require a separate persistent private signing key supplied through repository secrets; never commit that key or its passwords.
- Keep generated APKs and local build output out of Git.
