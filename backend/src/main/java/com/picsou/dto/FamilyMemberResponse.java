package com.picsou.dto;

import com.picsou.model.AppUser;
import com.picsou.model.FamilyMember;

public record FamilyMemberResponse(
    Long id,
    String displayName,
    String avatarColor,
    boolean managed,
    boolean hasLogin,
    boolean activated,
    String loginName,
    boolean mfaEnabled
) {
    public static FamilyMemberResponse from(FamilyMember member, AppUser user, boolean mfaEnabled) {
        return new FamilyMemberResponse(
            member.getId(),
            member.getDisplayName(),
            member.getAvatarColor(),
            member.isManaged(),
            user != null,
            user != null && user.isActivated(),
            user != null ? user.getUsername() : null,
            mfaEnabled
        );
    }
}
