import type { FamilyMemberItem } from './api'

/**
 * Members an admin may switch into (impersonate) from the sidebar profile switcher.
 *
 * Once a member has activated their own login (set their own password), their data
 * becomes private to them — they are no longer switchable, even by the admin. This
 * mirrors the authoritative backend guard in `UserContext.getMemberIdOverride`, which
 * rejects `?memberId=X` for an activated member with HTTP 403.
 *
 * Independent members remain visible elsewhere (Family settings, family dashboard);
 * they are only removed from the "become this person" switcher.
 */
export function selectSwitchableMembers(members: FamilyMemberItem[]): FamilyMemberItem[] {
  return members.filter((m) => m.managed && !m.activated)
}
