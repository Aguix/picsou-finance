package com.picsou.service;

import com.picsou.config.CryptoEncryption;
import com.picsou.model.Aggregator;
import com.picsou.model.AggregatorSession;
import com.picsou.repository.AggregatorRepository;
import com.picsou.repository.AggregatorSessionRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Manages the price {@link Aggregator}s and their API credentials ({@link AggregatorSession}).
 * Credentials are encrypted at rest via {@link CryptoEncryption}: the raw key/secret only ever
 * exist in memory on the way in ({@link #createSession}) and on the way out to an adapter
 * ({@link #enabledCredentials}) — the DB and any JSON response hold only ciphertext.
 *
 * <p>App-global, so these methods are <em>not</em> member-scoped (unlike almost everything else);
 * the admin panel gates who may read/write them. Adding a session for a fresh aggregator is how the
 * operator raises a rate limit — until then CoinGecko runs anonymous and Yahoo needs no key.</p>
 */
@Service
@RequiredArgsConstructor
public class AggregatorService {

    private static final Logger log = LoggerFactory.getLogger(AggregatorService.class);

    private final AggregatorRepository aggregatorRepository;
    private final AggregatorSessionRepository sessionRepository;
    private final CryptoEncryption encryption;

    /** All aggregators, ordered by key — for the admin panel. */
    @Transactional(readOnly = true)
    public List<Aggregator> listAggregators() {
        return aggregatorRepository.findAll(Sort.by("aggregatorKey"));
    }

    /** Every session of an aggregator — for the admin panel (secrets stay encrypted / unserialized). */
    @Transactional(readOnly = true)
    public List<AggregatorSession> sessionsFor(String aggregatorKey) {
        return sessionRepository.findByAggregator_AggregatorKeyOrderByIdAsc(aggregatorKey);
    }

    /**
     * Add a credential session to an aggregator, encrypting the key/secret before persisting.
     * Blank key/secret are stored as {@code null} (e.g. a key-only aggregator, or Yahoo which needs
     * neither). Throws if the aggregator key is unknown.
     */
    @Transactional
    public AggregatorSession createSession(String aggregatorKey, String label, String apiKey, String apiSecret) {
        Aggregator aggregator = aggregatorRepository.findByAggregatorKey(aggregatorKey)
            .orElseThrow(() -> new IllegalArgumentException("Unknown aggregator '" + aggregatorKey + "'."));
        AggregatorSession session = AggregatorSession.builder()
            .aggregator(aggregator)
            .label(blankToNull(label))
            .apiKey(encryptOrNull(apiKey))
            .apiSecret(encryptOrNull(apiSecret))
            .build();
        AggregatorSession saved = sessionRepository.save(session);
        log.info("Added credential session {} for aggregator '{}'", saved.getId(), aggregatorKey);
        return saved;
    }

    /** Pause or resume a single credential session without deleting it. */
    @Transactional
    public void setSessionEnabled(Long sessionId, boolean enabled) {
        AggregatorSession session = sessionRepository.findById(sessionId)
            .orElseThrow(() -> new IllegalArgumentException("No aggregator session with id " + sessionId + "."));
        session.setEnabled(enabled);
        sessionRepository.save(session);
    }

    /** Forget a credential session entirely. */
    @Transactional
    public void deleteSession(Long sessionId) {
        sessionRepository.deleteById(sessionId);
        log.info("Deleted aggregator credential session {}", sessionId);
    }

    /** Pause or resume a whole aggregator (its sessions are skipped while it is disabled). */
    @Transactional
    public void setAggregatorEnabled(String aggregatorKey, boolean enabled) {
        Aggregator aggregator = aggregatorRepository.findByAggregatorKey(aggregatorKey)
            .orElseThrow(() -> new IllegalArgumentException("Unknown aggregator '" + aggregatorKey + "'."));
        aggregator.setEnabled(enabled);
        aggregatorRepository.save(aggregator);
    }

    /**
     * Decrypted credentials for the price adapter, or {@link Optional#empty()} when the aggregator is
     * unknown or <em>disabled</em> — the adapter must then make no call at all (not even an anonymous
     * one). When present, the list holds every enabled session oldest-first; an <em>empty</em> list
     * means the aggregator is enabled but has no key, so the adapter may use its anonymous tier. The
     * empty-Optional vs empty-list distinction is what lets "disable the aggregator" actually stop it
     * rather than silently drop to anonymous. The {@code sessionId} lets the caller track rate-limit
     * back-off (and least-recently-used rotation) per session.
     */
    @Transactional(readOnly = true)
    public Optional<List<SessionCredentials>> enabledCredentials(String aggregatorKey) {
        Aggregator aggregator = aggregatorRepository.findByAggregatorKey(aggregatorKey).orElse(null);
        if (aggregator == null || !aggregator.isEnabled()) {
            return Optional.empty();
        }
        return Optional.of(
            sessionRepository.findByAggregator_AggregatorKeyAndEnabledTrueOrderByIdAsc(aggregatorKey).stream()
                .map(s -> new SessionCredentials(s.getId(), decryptOrNull(s.getApiKey()), decryptOrNull(s.getApiSecret())))
                .toList());
    }

    private String encryptOrNull(String raw) {
        return (raw == null || raw.isBlank()) ? null : encryption.encrypt(raw.trim());
    }

    private String decryptOrNull(String cipher) {
        return cipher == null ? null : encryption.decrypt(cipher);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /** Decrypted credentials of one session, with its id so callers can track per-session state. */
    public record SessionCredentials(Long sessionId, String apiKey, String apiSecret) {}
}
