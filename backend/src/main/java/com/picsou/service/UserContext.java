package com.picsou.service;

import com.picsou.model.AppUser;
import com.picsou.model.FamilyMember;
import com.picsou.model.UserRole;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Request-scoped helper to access the current authenticated user and their family member.
 */
@Component
public class UserContext {

    public AppUser currentUser() {
        return (AppUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    public FamilyMember currentMember() {
        return currentUser().getMember();
    }

    public Long currentMemberId() {
        return currentMember().getId();
    }

    public boolean isAdmin() {
        return currentUser().getRole() == UserRole.ADMIN;
    }
}
