package com.picsou.service;

import com.picsou.model.AppUser;
import com.picsou.model.FamilyMember;
import com.picsou.model.UserRole;
import com.picsou.repository.AppUserRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.SharedResourceRepository;
import com.picsou.repository.SharingSettingsRepository;
import com.picsou.repository.UserMfaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class FamilyServiceTest {

    @Mock FamilyMemberRepository memberRepository;
    @Mock AppUserRepository userRepository;
    @Mock UserMfaRepository userMfaRepository;
    @Mock SharingSettingsRepository sharingSettingsRepository;
    @Mock SharedResourceRepository sharedResourceRepository;
    @Mock PasswordEncoder passwordEncoder;

    @InjectMocks FamilyService familyService;

    private FamilyMember member(String displayName) {
        return FamilyMember.builder()
            .id(3L)
            .displayName(displayName)
            .avatarColor("#6366f1")
            .managed(true)
            .build();
    }

    /** Captures the AppUser persisted by generateActivationToken and returns its username. */
    private String usernameFromActivation(String displayName) {
        when(memberRepository.findById(3L)).thenReturn(Optional.of(member(displayName)));
        when(userRepository.findByMemberId(3L)).thenReturn(Optional.empty());

        familyService.generateActivationToken(3L);

        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(userRepository).save(captor.capture());
        return captor.getValue().getUsername();
    }

    @Test
    void deriveUsername_slugifiesSimpleName() {
        assertThat(usernameFromActivation("Alice")).isEqualTo("alice");
    }

    @Test
    void deriveUsername_stripsAccentsAndSpaces() {
        assertThat(usernameFromActivation("Jean Dupont")).isEqualTo("jean.dupont");
    }

    @Test
    void deriveUsername_normalizesAccentedChars() {
        assertThat(usernameFromActivation("Élodie")).isEqualTo("elodie");
    }

    @Test
    void deriveUsername_appendsSuffixOnCollision() {
        when(memberRepository.findById(3L)).thenReturn(Optional.of(member("Alice")));
        when(userRepository.findByMemberId(3L)).thenReturn(Optional.empty());
        // "alice" is taken, "alice.2" is free
        when(userRepository.existsByUsername("alice")).thenReturn(true);
        when(userRepository.existsByUsername("alice.2")).thenReturn(false);

        familyService.generateActivationToken(3L);

        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getUsername()).isEqualTo("alice.2");
    }

    @Test
    void deriveUsername_fallsBackToUserWhenNameHasNoAlphanumerics() {
        assertThat(usernameFromActivation("!!!")).isEqualTo("user");
    }

    // ─── deleteMember ───────────────────────────────────────────────────

    private AppUser appUser(UserRole role) {
        return AppUser.builder().role(role).activated(true).build();
    }

    @Test
    void deleteMember_activatedMember_deletes() {
        FamilyMember target = member("Bob");
        when(memberRepository.findById(3L)).thenReturn(Optional.of(target));
        when(userRepository.findByMemberId(3L)).thenReturn(Optional.of(appUser(UserRole.MEMBER)));

        familyService.deleteMember(3L, 1L);

        verify(memberRepository).delete(target);
    }

    @Test
    void deleteMember_self_throwsForbidden() {
        when(memberRepository.findById(3L)).thenReturn(Optional.of(member("Self")));

        assertThatThrownBy(() -> familyService.deleteMember(3L, 3L))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Cannot delete your own account");

        verify(memberRepository, never()).delete(any());
    }

    @Test
    void deleteMember_lastAdmin_throwsForbidden() {
        when(memberRepository.findById(3L)).thenReturn(Optional.of(member("TheAdmin")));
        when(userRepository.findByMemberId(3L)).thenReturn(Optional.of(appUser(UserRole.ADMIN)));
        when(userRepository.countByRole(UserRole.ADMIN)).thenReturn(1L);

        assertThatThrownBy(() -> familyService.deleteMember(3L, 1L))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Cannot delete the last administrator");

        verify(memberRepository, never()).delete(any());
    }

    @Test
    void deleteMember_nonLastAdmin_deletes() {
        FamilyMember target = member("SecondAdmin");
        when(memberRepository.findById(3L)).thenReturn(Optional.of(target));
        when(userRepository.findByMemberId(3L)).thenReturn(Optional.of(appUser(UserRole.ADMIN)));
        when(userRepository.countByRole(UserRole.ADMIN)).thenReturn(2L);

        familyService.deleteMember(3L, 1L);

        verify(memberRepository).delete(target);
    }

    @Test
    void deleteMember_notFound_throws() {
        when(memberRepository.findById(3L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> familyService.deleteMember(3L, 1L))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Member not found");
    }

    /**
     * Guards the regression that returned 500: when the target has a login, the
     * {@link AppUser} (loaded for the last-admin guard) must be deleted BEFORE the
     * member. Deleting the member first leaves a managed user pointing at a removed,
     * non-nullable {@code @OneToOne} → Hibernate throws TransientObjectException at flush.
     */
    @Test
    void deleteMember_withLogin_deletesUserBeforeMember() {
        FamilyMember target = member("Bob");
        AppUser user = appUser(UserRole.MEMBER);
        when(memberRepository.findById(3L)).thenReturn(Optional.of(target));
        when(userRepository.findByMemberId(3L)).thenReturn(Optional.of(user));

        familyService.deleteMember(3L, 1L);

        InOrder order = inOrder(userRepository, memberRepository);
        order.verify(userRepository).delete(user);
        order.verify(memberRepository).delete(target);
    }

    @Test
    void deleteMember_managedWithoutLogin_deletesOnlyMember() {
        FamilyMember target = member("Child");
        when(memberRepository.findById(3L)).thenReturn(Optional.of(target));
        when(userRepository.findByMemberId(3L)).thenReturn(Optional.empty());

        familyService.deleteMember(3L, 1L);

        verify(userRepository, never()).delete(any());
        verify(memberRepository).delete(target);
    }
}
