# Feature: Multi-account family system

> Last updated: 2026-04-22

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

1. Admin creates a managed profile (no login)
2. Admin clicks "Create login" → generates an activation token stored on `AppUser`
3. Admin shares the activation link (`/activate/{token}`)
4. The managed person opens the link, sets a password
5. `AppUser.isManaged` is set to false, `isActivated` to true
6. The person can now log in independently

Once activated, a managed member becomes **independent** (`isManaged=true && hasLogin && activated`). The admin can no longer delete their profile or regenerate their activation link. `FamilyService.deleteMember()` enforces this with a 403 guard.

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
- **Cannot delete an activated member**: `FamilyService.deleteMember()` throws 403 if the target member has an activated `AppUser`. The UI hides the delete button for `isIndependent` members, but the backend is the authoritative guard.
- **Yahoo Finance null closes**: Yahoo can return `null` in historical price arrays for non-trading days. Must check `close == null` before unboxing to avoid NPE.
- **Profile switch cache**: TanStack Query cache is global. Without `invalidateQueries()` on switch, the old member's data persists visually.

## Tests

- `GoalServiceTest` — goal CRUD scoped by memberId

## Links

- Related ADR: `docs/decisions/2026-01-01-single-user-jwt-cookies.md` (extended for multi-member)
