# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| `1.0.x` | Yes |
| < `1.0` | No |

Security patches are applied to the latest release on the `main` branch.

## Reporting a Vulnerability

**Do not open a public GitHub issue for security vulnerabilities.**

Report privately using one of:

- [GitHub Security Advisory](../../security/advisories/new) (preferred)
- Email the maintainer directly (see GitHub profile)

Please include:

- A clear description of the vulnerability
- Steps to reproduce
- Potential impact (e.g., data exposure, auth bypass, injection)
- A suggested fix if you have one

You will receive an initial response within **72 hours**. Critical vulnerabilities are targeted for a patch within **7 days**.

## Threat Model

Picsou is designed for **single-user, self-hosted deployment** on a trusted local network.

**In scope:**
- Protection of financial data at rest (database, API secrets)
- Secure authentication (JWT, cookie-based)
- Input validation and injection prevention
- Secret management (env vars, never hardcoded)

**Out of scope:**
- Multi-tenant isolation (the app is single-user)
- Protection against a compromised host or network (assumed trusted)
- DDoS mitigation (behind a home firewall)

## Security Measures

| Measure | Implementation |
|---------|---------------|
| Authentication | JWT in `HttpOnly; Secure; SameSite=Strict` cookies |
| Token rotation | Refresh token rotation on every use |
| Rate limiting | Bucket4j on `/api/auth/login` (5 attempts / 15 min) and `/api/sync/**` |
| Encryption at rest | AES-256-GCM for crypto exchange API secrets (mandatory key at startup) |
| SQL injection | JPA/Hibernate parameterized queries, no raw SQL |
| XSS | React's built-in escaping, CSP headers via Nginx |
| CSRF | SameSite cookies + Spring CSRF protection |
| Secrets | All credentials via environment variables, never in source code |
| Bank credentials | Enable Banking tokens are session-scoped, never persisted |
| Logging | No PII or financial balances in application logs |
| Dependencies | GitHub Dependabot enabled for automated vulnerability alerts |

## Deployment Security Checklist

- [ ] Change all default passwords in `.env`
- [ ] Generate a unique `JWT_SECRET` with `openssl rand -base64 48`
- [ ] Generate a `CRYPTO_ENCRYPTION_KEY` with `openssl rand -base64 32`
- [ ] Use bcrypt cost 12+ for `APP_PASSWORD_HASH`
- [ ] Keep the `.env` file out of version control (already in `.gitignore`)
- [ ] Do not expose the app on the public internet without a reverse proxy with TLS
- [ ] Restrict PostgreSQL access to the Docker network (default in `docker-compose.yml`)
- [ ] Regularly update Docker images and dependencies
