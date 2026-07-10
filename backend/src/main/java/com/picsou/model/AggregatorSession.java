package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One set of API credentials for an {@link Aggregator}. Several sessions per aggregator are allowed
 * so rate limits can be spread over multiple keys — at call time an adapter picks an enabled session
 * and applies its key, tracking rate-limit back-off per session.
 *
 * <p>{@link #apiKey}/{@link #apiSecret} hold AES-GCM ciphertext (encrypted at rest via
 * {@code CryptoEncryption}) and are never serialized. Both are nullable: Yahoo needs no key, and an
 * aggregator may use only a key without a secret. App-global — no member scoping.</p>
 */
@Entity
@Table(name = "aggregator_session")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"apiKey", "apiSecret"})   // never log the ciphertext secrets
public class AggregatorSession extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "aggregator_id", nullable = false)
    private Aggregator aggregator;

    /** Optional human label to tell several keys of the same aggregator apart in the admin panel. */
    @Column(length = 100)
    private String label;

    /** AES-GCM ciphertext of the API key; nullable. Never serialized. */
    @JsonIgnore
    @Column(name = "api_key", length = 500)
    private String apiKey;

    /** AES-GCM ciphertext of the API secret; nullable. Never serialized. */
    @JsonIgnore
    @Column(name = "api_secret", length = 500)
    private String apiSecret;

    /** Last time this session was used for a successful sync; informational / rotation hint. */
    @Column(name = "last_sync_at")
    private Instant lastSyncAt;

    /** False pauses this single key without deleting it. */
    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;
}
