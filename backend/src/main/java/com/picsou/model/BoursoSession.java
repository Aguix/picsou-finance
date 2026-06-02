package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "bourso_session")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BoursoSession extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private FamilyMember member;

    /** Serialized BoursoBank session cookies, encrypted at rest via CryptoEncryption. */
    @Column(name = "session_cookies", nullable = false, length = 4000)
    private String sessionCookies;

    /** Approximate session expiry — BoursoBank sessions last several weeks. */
    @Column(name = "expires_at")
    private Instant expiresAt;
}
