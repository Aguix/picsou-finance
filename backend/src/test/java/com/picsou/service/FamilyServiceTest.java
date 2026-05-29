package com.picsou.service;

import com.picsou.model.AppUser;
import com.picsou.model.FamilyMember;
import com.picsou.repository.AppUserRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.SharedResourceRepository;
import com.picsou.repository.SharingSettingsRepository;
import com.picsou.repository.UserMfaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
}
