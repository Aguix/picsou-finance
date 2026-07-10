package com.picsou.service;

import com.picsou.config.CryptoEncryption;
import com.picsou.model.Aggregator;
import com.picsou.model.AggregatorSession;
import com.picsou.repository.AggregatorRepository;
import com.picsou.repository.AggregatorSessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AggregatorServiceTest {

    @Mock AggregatorRepository aggregatorRepository;
    @Mock AggregatorSessionRepository sessionRepository;
    @Mock CryptoEncryption encryption;

    private AggregatorService service() {
        return new AggregatorService(aggregatorRepository, sessionRepository, encryption);
    }

    private static Aggregator aggregator(boolean enabled) {
        return Aggregator.builder().aggregatorKey("coingecko").displayName("CoinGecko").enabled(enabled).build();
    }

    @Test
    void createSession_encryptsKeyAndSecretBeforePersisting() {
        when(aggregatorRepository.findByAggregatorKey("coingecko")).thenReturn(Optional.of(aggregator(true)));
        when(encryption.encrypt("raw-key")).thenReturn("enc-key");
        when(encryption.encrypt("raw-secret")).thenReturn("enc-secret");
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service().createSession("coingecko", "demo", "raw-key", "raw-secret");

        ArgumentCaptor<AggregatorSession> captor = ArgumentCaptor.forClass(AggregatorSession.class);
        verify(sessionRepository).save(captor.capture());
        AggregatorSession saved = captor.getValue();
        assertThat(saved.getApiKey()).isEqualTo("enc-key");         // ciphertext, never the raw value
        assertThat(saved.getApiSecret()).isEqualTo("enc-secret");
        assertThat(saved.getLabel()).isEqualTo("demo");
        assertThat(saved.isEnabled()).isTrue();
    }

    @Test
    void createSession_blankSecretIsStoredAsNull_withoutEncrypting() {
        when(aggregatorRepository.findByAggregatorKey("coingecko")).thenReturn(Optional.of(aggregator(true)));
        when(encryption.encrypt("raw-key")).thenReturn("enc-key");
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service().createSession("coingecko", null, "raw-key", "   ");

        ArgumentCaptor<AggregatorSession> captor = ArgumentCaptor.forClass(AggregatorSession.class);
        verify(sessionRepository).save(captor.capture());
        assertThat(captor.getValue().getApiSecret()).isNull();
        assertThat(captor.getValue().getLabel()).isNull();
        verify(encryption, never()).encrypt("   ");
    }

    @Test
    void createSession_unknownAggregator_throws() {
        when(aggregatorRepository.findByAggregatorKey("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().createSession("nope", null, "k", "s"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("nope");
        verifyNoInteractions(sessionRepository);
    }

    @Test
    void enabledCredentials_decryptsAndCarriesSessionId() {
        when(aggregatorRepository.findByAggregatorKey("coingecko")).thenReturn(Optional.of(aggregator(true)));
        AggregatorSession s = AggregatorSession.builder().id(7L).apiKey("enc-key").apiSecret("enc-secret").enabled(true).build();
        when(sessionRepository.findByAggregator_AggregatorKeyAndEnabledTrueOrderByIdAsc("coingecko"))
            .thenReturn(List.of(s));
        when(encryption.decrypt("enc-key")).thenReturn("raw-key");
        when(encryption.decrypt("enc-secret")).thenReturn("raw-secret");

        List<AggregatorService.SessionCredentials> creds = service().enabledCredentials("coingecko");

        assertThat(creds).hasSize(1);
        assertThat(creds.get(0).sessionId()).isEqualTo(7L);
        assertThat(creds.get(0).apiKey()).isEqualTo("raw-key");
        assertThat(creds.get(0).apiSecret()).isEqualTo("raw-secret");
    }

    @Test
    void enabledCredentials_emptyWhenAggregatorDisabled_withoutTouchingSessions() {
        when(aggregatorRepository.findByAggregatorKey("coingecko")).thenReturn(Optional.of(aggregator(false)));

        assertThat(service().enabledCredentials("coingecko")).isEmpty();
        verifyNoInteractions(sessionRepository);
    }

    @Test
    void enabledCredentials_emptyWhenAggregatorUnknown() {
        when(aggregatorRepository.findByAggregatorKey("ghost")).thenReturn(Optional.empty());

        assertThat(service().enabledCredentials("ghost")).isEmpty();
        verifyNoInteractions(sessionRepository);
    }

    @Test
    void setSessionEnabled_togglesAndSaves() {
        AggregatorSession s = AggregatorSession.builder().id(3L).enabled(true).build();
        when(sessionRepository.findById(3L)).thenReturn(Optional.of(s));

        service().setSessionEnabled(3L, false);

        assertThat(s.isEnabled()).isFalse();
        verify(sessionRepository).save(s);
    }

    @Test
    void setSessionEnabled_unknownId_throws() {
        when(sessionRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().setSessionEnabled(99L, true))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
