package com.picsou.service;

import com.picsou.config.CryptoEncryption;
import com.picsou.dto.FinaryCheckTotpResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.finary.FinaryApiSyncService;
import com.picsou.finary.FinaryPersistenceHelper;
import com.picsou.finary.client.FinaryApiClient;
import com.picsou.model.FamilyMember;
import com.picsou.model.FinarySession;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.FinarySessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FinaryApiSyncServiceTest {

    @Mock FinaryApiClient finaryApiClient;
    @Mock CryptoEncryption encryption;
    @Mock AccountRepository accountRepository;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock FinarySessionRepository finarySessionRepository;
    @Mock FinaryPersistenceHelper persistenceHelper;

    @InjectMocks FinaryApiSyncService service;

    @Test
    void checkTotp_returnsTrue_whenTotpRequired() {
        FinarySession session = FinarySession.builder()
            .member(FamilyMember.builder().id(1L).build())
            .email("enc-email")
            .password("enc-pass")
            .status("CONNECTED")
            .build();
        when(finarySessionRepository.findByMemberId(1L)).thenReturn(Optional.of(session));
        when(encryption.decrypt("enc-email")).thenReturn("user@example.com");
        when(encryption.decrypt("enc-pass")).thenReturn("secret");
        when(finaryApiClient.checkTotpRequired("user@example.com", "secret")).thenReturn("sign-in-id-123");

        FinaryCheckTotpResponse result = service.checkTotp(1L);

        assertThat(result.totpRequired()).isTrue();
    }

    @Test
    void checkTotp_returnsFalse_whenNoTotpNeeded() {
        FinarySession session = FinarySession.builder()
            .member(FamilyMember.builder().id(1L).build())
            .email("enc-email")
            .password("enc-pass")
            .status("CONNECTED")
            .build();
        when(finarySessionRepository.findByMemberId(1L)).thenReturn(Optional.of(session));
        when(encryption.decrypt("enc-email")).thenReturn("user@example.com");
        when(encryption.decrypt("enc-pass")).thenReturn("secret");
        when(finaryApiClient.checkTotpRequired("user@example.com", "secret")).thenReturn(null);

        FinaryCheckTotpResponse result = service.checkTotp(1L);

        assertThat(result.totpRequired()).isFalse();
    }

    @Test
    void checkTotp_throwsResourceNotFoundException_whenNoSession() {
        when(finarySessionRepository.findByMemberId(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.checkTotp(99L))
            .isInstanceOf(ResourceNotFoundException.class);
    }
}
