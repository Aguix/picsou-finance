package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "user_mfa")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserMfa extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private AppUser user;

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = false;

    @Column(name = "totp_secret_enc", nullable = false, columnDefinition = "TEXT")
    private String totpSecretEnc;

    @Column(name = "last_used_step")
    private Long lastUsedStep;

    @Column(name = "enrolled_at")
    private Instant enrolledAt;
}
