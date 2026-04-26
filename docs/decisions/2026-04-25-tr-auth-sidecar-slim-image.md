# ADR: tr-auth as isolated sidecar with Chromium-only image

> Date: 2026-04-25
> Status: ✅ Active

## Context

Trade Republic's API requires an AWS WAF browser challenge on every auth request. This forces a real headless browser (Chromium) to obtain the `x-aws-waf-token` cookie before each API call. The previous approach embedded tr-auth inside the main `picsou:latest` image using `mcr.microsoft.com/playwright/python:v1.44.0-jammy` as the runtime base, which pre-installs Chromium + Firefox + WebKit. This bloated the release archive to ~969MB compressed.

## Decision

1. **tr-auth runs as a separate container** (`services/tr-auth/`), not as a process inside the main image.
2. **The tr-auth image uses `python:3.12-slim`** with only Chromium installed via `playwright install chromium` (no Firefox, no WebKit).
3. **The main `picsou:latest` image uses `eclipse-temurin:21-jre-jammy`** with no Python or Playwright dependency.

## Alternatives considered

### Keep tr-auth inside the main image (previous state)

- **Pros**: single container to deploy and operate.
- **Cons**: forces the main image to use the 1.5GB Playwright base image. Every user (including those without a Trade Republic account) downloads ~800MB of browser binaries they may not need. Runtime is a 3-process supervisor soup inside one container.

### Keep the Playwright official base image but only for tr-auth

- **Pros**: simpler Dockerfile (no manual dep listing).
- **Cons**: `mcr.microsoft.com/playwright/python` installs all three browsers unconditionally. No supported way to skip Firefox/WebKit at image level without overriding `playwright install` anyway.

### Replace Playwright with a lighter WAF-bypass approach

- **Pros**: could eliminate the browser entirely.
- **Cons**: the AWS WAF challenge runs arbitrary JavaScript that must evaluate in a real V8 context. No stable pure-Python alternative exists; any approach would be fragile and subject to breakage on WAF SDK updates.

## Reasoning

tr-auth is already architecturally separate from the main app (its own language, own process, own port). Isolating it into its own container is the natural boundary. Once isolated, the main image drops the Playwright dependency and becomes JRE-only.

For the sidecar image, only `p.chromium.launch()` is ever called — Firefox and WebKit are dead weight. `python:3.12-slim` + `playwright install chromium --with-deps` would be ideal but fails on Debian bookworm due to Ubuntu-specific font packages (`ttf-unifont`, `ttf-ubuntu-font-family`). Installing Chromium system deps manually via apt then running `playwright install chromium` (without `--with-deps`) achieves the same result cleanly.

## Trade-offs accepted

- Two images to build, distribute, and maintain instead of one.
- Users must run `docker compose up` (not just `docker run picsou`) to get tr-auth — documented in `docker/docker-compose.yml`.
- The `docker save` export includes both images; TR-less users carry tr-auth even if unused (547MB total vs splitting further).

## Consequences

- `picsou:latest`: `eclipse-temurin:21-jre-jammy`, supervisord manages Nginx + backend only (2 processes).
- `docker-tr-auth`: `python:3.12-slim`, Chromium only via `playwright install chromium`.
- Release archive: **969MB → 547MB** (−43%).
- `TR_AUTH_URL` env var in entrypoint defaults to `http://127.0.0.1:8001` for backward compat; docker-compose overrides it to `http://tr-auth:8001`.
