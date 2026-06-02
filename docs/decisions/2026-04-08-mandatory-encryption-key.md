# ADR: Mandatory encryption key at startup

> Date: 2026-04-08
> Status: ✅ Active

## Context

`CryptoEncryption` previously accepted a missing `CRYPTO_ENCRYPTION_KEY` at startup: it logged a warning and set `key = null`. The `encrypt()` method would throw later at call time, but the application could start and serve requests without any encryption capability. This created two risks:

1. **Silent misconfiguration**: A deployment without the key appears healthy. The operator discovers the problem only when adding a crypto exchange or authenticating Trade Republic -- possibly weeks later.
2. **Expanded encryption scope**: As of 2026-04-08, encryption covers not just crypto API secrets but also API keys, Trade Republic session tokens, and refresh tokens. The key is no longer optional for a subset of features -- it is required for core functionality.

## Decision

`CryptoEncryption` throws `IllegalStateException` at startup if `CRYPTO_ENCRYPTION_KEY` is not set or is blank. The application refuses to start without it.

The error message includes the generation command: `openssl rand -base64 32`.

## Alternatives considered

### Keep the warning, fail at call time (previous behavior)

- **Pros**: App starts without the key; users who don't use crypto/TR are unaffected
- **Cons**: Silent misconfiguration; runtime surprise when user tries to store credentials; easy to miss a log warning in production

### Conditional startup validation (fail only if crypto/TR features are enabled)

- **Pros**: Users who only use bank sync don't need the key
- **Cons**: Adds configuration complexity (`app.features.crypto.enabled` flag or similar); the key is trivial to generate -- requiring it unconditionally is simpler than maintaining conditional logic

### Plaintext fallback with degraded mode

- **Pros**: App works without the key; credentials stored unencrypted
- **Cons**: Unacceptable for a finance app; defeats the purpose of encryption; the previous warning-based approach was essentially this

## Reasoning

For a personal finance application handling exchange credentials and brokerage tokens, encryption is not optional. The cost of requiring the key (one `openssl` command, documented in `.env.example`) is negligible compared to the risk of silent plaintext storage. Fail-fast at startup is the standard approach for mandatory configuration in Spring Boot applications.

The conditional approach was rejected because the added complexity (feature flags, conditional bean creation) is not justified when the fix is a single command to generate a key.

## Trade-offs accepted

- **Users who don't use crypto or TR still need to set the key.** This is a minor inconvenience (one env var) but keeps the configuration simple and the security posture uniform.
- **Existing deployments without the key will fail to start after upgrade.** This is intentional -- the error message is actionable and the fix takes 10 seconds.

## Consequences

- `CryptoEncryption` constructor throws if key is missing -- Spring context fails to initialize
- `encrypt()` and `decrypt()` no longer check for `key == null` (simplified code)
- `.env.example` documents the key generation command
- All environments (dev, test, prod) must provide the key
- Related feature note: [encryption-at-rest.md](../features/encryption-at-rest.md)
- Updates ADR: [AES-256-GCM for crypto secrets](./2026-03-01-aes-gcm-crypto-secrets.md)
