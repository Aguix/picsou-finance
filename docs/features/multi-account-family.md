# Feature: Multi-account family system

> Last updated: 2026-05-31

## Context

Allows a single Picsou instance to host multiple family members under one admin account. The admin can create managed profiles (children, spouse without login), and optionally upgrade them to full login accounts via an activation link. Data is scoped per member; sharing is configurable.

## How it works

### Identity model

Two-tier identity: `AppUser` (authentication) → `FamilyMember` (domain identity).

- `FamilyMember` — profile with displayName, avatarColor, `isManaged` flag
- `AppUser` — login credentials, links to exactly one FamilyMember via `member_id`
- `managed=false` → has login (active member)
- `managed=true` → no login, managed by admin (child, shared account)

Every data entity (Account, Goal, Requisition, etc.) has a `member_id` FK. All service methods take `Long memberId` and use repository methods like `findByIdAndMemberId()`.

### Member scoping (UserContext)

`UserContext.currentMemberId()` is called by every controller to scope queries.

When an admin switches to a managed profile in the UI, the frontend sends `?memberId=X` on every API request (Axios interceptor in `api-client.ts`). The backend `UserContext.getMemberIdOverride()` checks:
1. Is the current user an admin?
2. Is there a `memberId` query param?
3. If both → return the override memberId instead of the admin's own

Non-admin users always use their own memberId (override is ignored).

### Sharing system

Members choose what to share via `SharingSettings` per resource type (`ACCOUNT`, `GOAL`):

- `NONE` — private (default)
- `ALL` — share everything of that type
- `MANUAL` — share only specific resources via `shared_resource` table

The `FamilyViewService` aggregates shared data for the family dashboard.

### Profile activation flow

1. Admin creates a managed profile (no login). From the **Admin → Members** section
   the "Create user" button does steps 1–2 in one geste (profile + login), then
   shows the activation link; *Settings → Family* still creates a bare profile.
2. Admin clicks "Create login" → generates an activation token stored on `AppUser`.
   The login **username is derived from the display name** ("Jean Dupont" →
   `jean.dupont`, slugified + made unique with a `.N` suffix) instead of the legacy
   `member_<id>`. See `FamilyService.deriveUsername` and [admin-page.md](./admin-page.md).
3. Admin shares the activation link (`/activate/{token}`)
4. The managed person opens the link, sets a password
5. `AppUser.isManaged` is set to false, `isActivated` to true
6. The person can now log in independently

Once activated, a managed member becomes **independent** (`isManaged=true && hasLogin && activated`). The admin can no longer regenerate their activation link (`generateActivationToken` rejects activated users) or **impersonate** them. The admin **can** still delete an independent member — see "Member deletion" below.

### Admin access boundary (independent members are private)

Activation also revokes the admin's ability to **impersonate** the member. As soon
as a member has set their own password (`activated=true`), the admin can no longer
switch into their profile and browse their data:

- **Backend (authoritative):** `UserContext.getMemberIdOverride()` honors `?memberId=X`
  only when X is the admin's own member id, or when member X has no activated login
  (a true managed profile: child / no-login / login created but not yet activated).
  Overriding to an **activated** member throws `403 "Cannot access an independent
  member's data"`. This is the single choke point through which every controller
  scopes data (`currentMemberId()`), so all endpoints are covered at once.
- **Frontend (UX):** the sidebar profile switcher is built from
  `selectSwitchableMembers()` (`features/family/members.ts` = `managed && !activated`),
  so independent members **disappear** from the switcher. They remain listed in Family
  settings and on the family dashboard.

This is an automatic confidentiality guarantee, not a toggle. **Voluntary sharing is
unaffected** — anything an independent member chooses to share via `SharingSettings`
still reaches the admin through `FamilyViewService` (family dashboard). The admin's
password-reset capability also stays intact and does not re-open access: a reset keeps
`activated=true` (it only issues a fresh token), so the boundary holds.

### Password reset by an admin

`POST /api/family/members/{id}/reset-password` (admin-only) issues a fresh
`activationToken` with a 7-day expiry on an existing `AppUser` row. The token
reuses the `/activate/{token}` flow so the user lands on the same screen and
sets a new password. The current `passwordHash` is **not** cleared — the old
credential keeps working until the user actually completes the reset, so a
mistakenly-issued reset link does not lock anyone out. Distinct from
`POST /members/{id}/activate`, which deliberately rejects already-activated
users (`FamilyService.generateActivationToken` line 92).

`FamilyMemberResponse` now exposes `loginName` (= `AppUser.username` or `null`)
so the admin UI can show both the display name and the login side-by-side.

### Member deletion

`DELETE /api/family/members/{id}` (admin-only) removes a member **and, by DB
cascade, all owned data** — every owner table's `member_id` FK is declared
`ON DELETE CASCADE` (see `V20`), so deleting the member wipes accounts, goals,
requisitions, bank/crypto sessions, wallets, debts, sharing settings and
contributions.

**Deletion order matters (JPA flush ordering).** `FamilyService.deleteMember`
loads the target's `AppUser` into the persistence context to run the last-admin
guard. `AppUser.member` is a **non-nullable** `@OneToOne` on `member_id`, so if we
deleted the member while that *managed* `AppUser` still referenced it, Hibernate
would throw `TransientObjectException` **at flush — before any SQL runs**, so the
DB `ON DELETE CASCADE` never gets a chance. The method therefore deletes the loaded
`AppUser` explicitly first, *then* the member:

```java
AppUser user = userRepository.findByMemberId(id).orElse(null);
// ... guards ...
if (user != null) userRepository.delete(user);  // the only managed entity referencing the member
memberRepository.delete(member);                // DB ON DELETE CASCADE wipes the rest
```

Other owned rows (accounts, goals, sessions…) stay **unloaded** in this transaction,
so no managed entity references the removed member at flush and the DB cascade
handles them.

**Regression coverage (and why it's not a flush test).** The ideal guard would
persist a member + linked `AppUser`, flush, and assert `deleteMember` succeeds —
reproducing `TransientObjectException` on the pre-fix code. That isn't feasible
here: the schema is PostgreSQL-specific (`TIMESTAMPTZ`, native enum types, Flyway
`ON DELETE CASCADE`) and does not replay on the H2 in-memory database used for
tests, and the project deliberately avoids Testcontainers (see
`docs/conventions/testing.md`) — every test is a Mockito unit test. The regression
is therefore guarded by `FamilyServiceTest.deleteMember_withLogin_deletesUserBeforeMember`,
which uses Mockito `InOrder` to assert `userRepository.delete(user)` is invoked
**before** `memberRepository.delete(member)`. It cannot reproduce the flush
exception (no live `flush()`), but it locks the fix: it fails the instant the two
deletes are reordered back to the buggy sequence.
`deleteMember_managedWithoutLogin_deletesOnlyMember` covers the no-login branch
(only the member is deleted, no `AppUser`).

An activated (independent) member is **no longer protected** from deletion — the
admin who runs the instance may remove anyone. `FamilyService.deleteMember(id,
requesterMemberId)` keeps only two guards so the instance stays usable:

1. **Self-delete** — an admin cannot delete their own member (`id == requesterMemberId`
   → 403 "Cannot delete your own account"), avoiding a lock-out.
2. **Last administrator** — if the target's `AppUser.role == ADMIN` and
   `AppUserRepository.countByRole(ADMIN) <= 1`, deletion is refused (403 "Cannot
   delete the last administrator").

Frontend: both member-management UIs (`admin/sections/MembersSection.tsx` and
`settings/FamilySettingsPage.tsx`) show the delete action for any non-self member.
Because deletion of an **activated** member is irreversible and destroys private
data, the shared `ConfirmDialog` is given a `confirmPhrase` (the member's display
name) that the admin must retype before the destructive call is enabled.

### Username change

`PATCH /api/auth/username` updates the username of the currently authenticated user:
1. Validates format and uniqueness (409 if taken)
2. Updates `AppUser.username`
3. **Re-issues both JWT cookies** (access + refresh) with the new username as subject

Step 3 is critical: without it, the next request would fail because the old JWT still contains the old username, which no longer exists in the DB.

### Key files

**Backend:**
- `model/FamilyMember.java` — member profile entity
- `model/AppUser.java` — login entity (username, passwordHash, role, member FK)
- `model/SharingSettings.java` — per-resource sharing level
- `model/SharedResource.java` — individual resource sharing (MANUAL mode)
- `service/UserContext.java` — request-scoped helper, handles memberId override for admins
- `service/FamilyService.java` — member CRUD, activation tokens, sharing settings
- `service/FamilyViewService.java` — family dashboard aggregation
- `controller/FamilyController.java` — `/api/family/members` (admin-only), `/api/family/sharing`
- `controller/FamilyViewController.java` — `/api/family/dashboard`
- `controller/AuthController.java` — `/api/auth/activate/{token}`

**Frontend:**
- `stores/profile-store.ts` — `activeMemberId`, `viewMode` (own/managed/family)
- `features/family/hooks.ts` — TanStack Query hooks for members, sharing, dashboard
- `features/family/api.ts` — API functions
- `features/family/members.ts` — `selectSwitchableMembers()` (admin switcher excludes independent members)
- `components/layout/AppSidebar.tsx` — profile switcher in dropdown
- `lib/api-client.ts` — Axios interceptor adds `?memberId=X` when managed profile active
- `pages/settings/FamilySettingsPage.tsx` — member management + sharing config UI
- `pages/family/FamilyDashboardPage.tsx` — shared overview
- `pages/activation/ActivationPage.tsx` — activation flow for new members
- `pages/settings/SettingsPage.tsx` — username edit inline (pencil → input → save)

**Migrations:**
- `V20__create_family_system.sql` — creates family_member, sharing_settings, shared_resource, goal_contributor tables; adds member_id to all owner tables
- `V21__migrate_existing_data.sql` — creates admin member, links existing user, assigns all data to admin
- `V22__make_member_id_not_null.sql` — makes all member_id columns NOT NULL

### Flow: admin switching to managed profile

```
Admin clicks managed profile in sidebar dropdown
  → setActiveMember(memberId) in profile-store
  → queryClient.invalidateQueries() clears all TanStack Query cache
  → Axios interceptor reads activeMemberId from store
  → Adds ?memberId=X to every outgoing API request
  → UserContext.currentMemberId() sees admin + memberId param → returns X
  → All queries scoped to member X
  → Sidebar avatar/name updates to show managed profile
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Two-tier identity (AppUser + FamilyMember) | Separates auth concerns from domain identity; allows managed profiles without login | Single User entity with flags |
| `?memberId` query param for admin override | Zero changes to existing controllers (they all call `userContext.currentMemberId()`) | Custom header, separate endpoint, request-scoped bean |
| `@JsonIgnore` on all lazy entity relations | Prevents LazyInitializationException when entities are serialized directly (open-in-view is disabled) | Open-in-view: true, DTOs for every entity (too many for existing code) |
| `@JdbcTypeCode(SqlTypes.NAMED_ENUM)` on PG native enums | Required for Hibernate to properly cast Java enum → PG enum on writes | Default `@Enumerated(STRING)` sends varchar, PG rejects it |
| TanStack Query invalidation on profile switch | Simple, works for all queries at once | Adding memberId to every query key (invasive, many files to change) |

## Gotchas / Pitfalls

- **LazyInitializationException**: With `open-in-view: false`, any entity with `@ManyToOne(fetch = LAZY)` that gets serialized directly by a controller will 500. All lazy relation fields have `@JsonIgnore` as a safeguard. New entities with lazy relations MUST add `@JsonIgnore`.
- **PG native enum columns**: PostgreSQL enum types (`sharing_level`, `requisition_status`, `account_type`) require `@JdbcTypeCode(SqlTypes.NAMED_ENUM)` + `columnDefinition`. Without it, Hibernate sends a varchar and PG rejects the INSERT/UPDATE.
- **Admin-only endpoints**: `FamilyController` member management methods call `requireAdmin()`. If a non-admin hits these, they get 403. The frontend must guard UI accordingly (currently checks `user?.role === 'ADMIN'`).
- **Stale auth store**: The frontend caches user info (including role) at login time. Changing role in DB requires re-login to take effect in the UI.
- **Username change requires token rotation**: `PATCH /api/auth/username` must re-issue the JWT cookies. If you only update the DB row, the existing tokens still carry the old username — the filter can't find the user → immediate 401 on next request.
- **`isIndependent` in frontend must include `managed`**: The display logic for a member's status in `FamilySettingsPage` uses `isIndependent = member.managed && member.hasLogin && member.activated`. Without `managed`, admin users (who are also `hasLogin && activated`) would show "Compte indépendant" instead of "Administrateur".
- **Member deletion cascades everything — but order matters**: `FamilyService.deleteMember(id, requesterMemberId)` must delete the loaded `AppUser` **before** the member. `AppUser.member` is a non-nullable `@OneToOne`; deleting the member while a managed `AppUser` still references it makes Hibernate throw `TransientObjectException` at flush (before any SQL), so the DB `ON DELETE CASCADE` never runs. After the `AppUser` is removed, `memberRepository.delete(member)` cascades the rest (accounts, goals, sessions…) at the DB level. A Mockito `InOrder` test (`FamilyServiceTest.deleteMember_withLogin_deletesUserBeforeMember`) locks the ordering; a true flush test isn't possible because the PG-specific schema (`TIMESTAMPTZ`, native enums) won't replay on H2 (see "Member deletion" and `docs/conventions/testing.md`). Only two 403 guards remain — self-delete and last-admin (see "Member deletion"). Activated members are deletable (the old "cannot delete an activated member" rule was removed). The frontend requires retyping the display name (`ConfirmDialog confirmPhrase`) before deleting an activated member, and surfaces any backend error inside the dialog via `formatApiError` (see `docs/conventions/error-handling.md`).
- **Cannot impersonate an activated member**: `UserContext.getMemberIdOverride()` throws 403 when an admin's `?memberId=X` targets an activated (independent) member other than themselves. The sidebar hides them too (`selectSwitchableMembers`), but the backend is the authoritative guard — never rely on the frontend filter alone.
- **Yahoo Finance null closes**: Yahoo can return `null` in historical price arrays for non-trading days. Must check `close == null` before unboxing to avoid NPE.
- **Profile switch cache**: TanStack Query cache is global. Without `invalidateQueries()` on switch, the old member's data persists visually.

## Tests

- `GoalServiceTest` — goal CRUD scoped by memberId
- `FamilyServiceTest` — username derivation, activation/reset, and **member deletion**:
  `deleteMember_withLogin_deletesUserBeforeMember` (Mockito `InOrder` guard for the
  `TransientObjectException` fix), `deleteMember_managedWithoutLogin_deletesOnlyMember`,
  `deleteMember_self_throwsForbidden`, `deleteMember_lastAdmin_throwsForbidden`,
  `deleteMember_nonLastAdmin_deletes`, `deleteMember_activatedMember_deletes`,
  `deleteMember_notFound_throws`

## Links

- Related ADR: `docs/decisions/2026-01-01-single-user-jwt-cookies.md` (extended for multi-member)
