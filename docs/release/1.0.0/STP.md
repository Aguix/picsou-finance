# Software Test Plan (STP)

> Project: **Picsou** — version **1.0.0**
> Date: 2026-04-26
> Format: IEEE 829-style, condensed

---

## 1. Purpose & scope

This STP describes how Picsou 1.0.0 is verified before release. It
covers the backend (Java / Spring Boot), the frontend (React / Vite),
and the integrated end-to-end flow. Out of scope: load testing,
penetration testing (handled separately in
`docs/superpowers/` security reviews).

The plan is the contract between this codebase and any contributor or
release engineer running the suite.

---

## 2. Test items

| Item | Reference |
|------|-----------|
| Backend services & controllers | `backend/src/test/java/...` |
| Backend persistence | `@DataJpaTest` with H2 |
| Frontend components & hooks | `frontend/src/**/*.test.ts(x)` |
| End-to-end flows | `frontend/tests/e2e/` (Playwright) |
| Type safety | `bun run typecheck` (frontend) |
| Lint | `bun run lint` (frontend) |
| Build | `mvn package -DskipTests` + `bun run build` |

---

## 3. Features to test

### 3.1 Authentication & sessions (FR-1)

- Login success / wrong password / locked-out (rate limit 5/15min)
- 2FA enrolment, valid TOTP, invalid TOTP, recovery code
- Persistent token issued, silent re-auth, theft detection (re-using a
  stale token revokes the family)
- Password change → outstanding access tokens rejected (`tv` claim
  bumped); current device re-issued

### 3.2 Family & sharing (FR-2)

- Admin invites, activates, deactivates a member
- Member-scoped queries: a member cannot read another member's data by
  guessing IDs
- Sharing rules: `READ` vs. `READ_WRITE`
- Admin override `?memberId=X`

### 3.3 Setup wizard (FR-3)

- Fresh DB → all `/api/*` except `/api/setup/*` return 503
- Wizard finishes → `setup_state.completed = true`, filter inert
- Audit rows in `setup_audit`

### 3.4 Bank sync (FR-4)

- `initiate` builds an `authLink`
- `complete` exchanges the code, creates a `Requisition` `LINKED`
- Failed exchange → `Requisition` `FAILED`, retry succeeds
- Daily scheduler re-syncs all `LINKED` requisitions
- Powens disabled: setting `POWENS_CLIENT_ID` does NOT change which
  bean is injected (Enable Banking still primary)

### 3.5 Brokerage / crypto / wallets (FR-5)

- Trade Republic phone+PIN+SMS OTP via the sidecar
- Binance API key encrypted at rest; balance refresh
- BTC xpub / ETH / SOL address balance polling

### 3.6 Manual transactions (FR-6)

- Create / edit / delete; `is_manual = true` preserved across re-syncs
- BUY/SELL → `HoldingComputeService` updates positions; cost basis
  weighted average

### 3.7 Holdings & prices (FR-7)

- Yahoo via OpenFIGI ISIN→ticker
- CoinGecko for crypto
- 15-minute in-memory cache; second call within 15 min hits the cache

### 3.8 Goals & loans (FR-8)

- Auto-contribution from account balance delta
- Manual contribution
- Loan amortisation: principal + APR + fees → schedule, progress, cost
  summary; matches a hand-computed reference for a known case

### 3.9 Dashboard & history (FR-9)

- Time-range presets each return the expected window
- Demo mode renders without any backend call
- Mobile viewport (375px) renders without horizontal overflow

### 3.10 Data export (FR-10)

- Re-auth required (without it → 401)
- Export rate-limited
- ZIP contains every CSV; row counts match per-domain `count(*)`

### 3.11 Admin (FR-11)

- Update CORS at runtime → reflected in next CORS preflight without
  restart
- Reset another member's password → that member can log in with the
  new one
- Reset another member's MFA → MFA disabled for that member

---

## 4. Non-functional verification

### 4.1 Security

- Cookies: `HttpOnly`, `SameSite=Lax`, `Secure` (when configured)
- CSRF: disabled — verified the chain has no CSRF filter
- Stack traces never present in any response
- Encryption at rest: round-trip a known plaintext through
  `CryptoEncryption`; ciphertext is base64; tampering with the IV or tag
  raises `AEADBadTagException`
- Member-scoping smoke test: a member with token A cannot fetch a
  resource owned by member B

### 4.2 Performance

- Dashboard endpoint responds in < 1 s warm cache for 50 accounts
- Daily scheduled jobs do not block API traffic (Spring `@Async` /
  separate thread pool)

### 4.3 Reliability

- Flyway: applying V1 → V29 from scratch on Postgres 16 succeeds
- Killing the backend mid-sync leaves the DB in a consistent state
  (transactional boundaries respected)

---

## 5. Test approach

### 5.1 Backend

- **Unit:** Mockito with `@ExtendWith(MockitoExtension.class)`. Mock
  ports and repositories.
- **Integration:** `@DataJpaTest` with H2 for persistence; `@WebMvcTest`
  for controllers with `MockMvc`.
- **Convention:** see `docs/conventions/testing.md`.

```bash
cd backend
mvn test                            # full suite
mvn test -Dtest=GoalServiceTest     # single class
```

### 5.2 Frontend

- **Unit:** Vitest + Testing Library for components and hooks.
- **E2E:** Playwright against a built frontend + running backend.

```bash
cd frontend
bun run typecheck
bun run lint
bunx vitest run
bun run test:e2e
```

### 5.3 Manual smoke

A release candidate must pass a manual smoke pass on a fresh database:

1. Start with empty DB → wizard appears.
2. Complete wizard with admin user.
3. Enrol TOTP, log out, log back in with TOTP.
4. Add a manual account, a manual transaction, a goal.
5. Check the dashboard renders all the above.
6. Run `GET /api/me/export` → ZIP downloads, opens, contains every CSV.
7. Resize to a 375 px viewport → all pages still usable.

---

## 6. Pass / fail criteria

A release is GO when:

| Gate | Threshold |
|------|-----------|
| `mvn test` | 100% pass |
| `bunx vitest run` | 100% pass |
| `bun run typecheck` | 0 errors |
| `bun run lint` | 0 errors |
| Playwright E2E | 100% pass |
| Manual smoke (above) | All steps green |
| Latest security review | No confirmed high-severity issue open |

---

## 7. Suspension & resumption

If any mandatory gate fails:

1. Mark the build as NO-GO.
2. File the issue with the failing test ID and a minimal repro.
3. Fix on a `fix/...` branch and re-run the full suite (not just the
   failed test) before resuming.

---

## 8. Test deliverables

- JUnit XML reports under `backend/target/surefire-reports/`
- Vitest console + JSON report
- Playwright HTML report under `frontend/playwright-report/`
- Coverage (optional): JaCoCo for backend, c8 for frontend
- This STP, kept in sync with the SRS

---

## 9. Out of scope

- Load / stress testing
- Browser fuzzing
- Provider contract testing against live Enable Banking, Yahoo, etc.
  (mocked in tests)
- Any test of the disabled Powens / BoursoBank adapters
