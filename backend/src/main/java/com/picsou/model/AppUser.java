package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "app_user")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppUser extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @JsonIgnore
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false, unique = true)
    private FamilyMember member;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private UserRole role = UserRole.MEMBER;

    @Column(name = "is_activated", nullable = false)
    @Builder.Default
    private boolean activated = true;

    @Column(name = "activation_token", length = 64)
    private String activationToken;

    @Column(name = "activation_token_expires")
    private java.time.Instant activationTokenExpires;

    @Column(name = "acknowledged_warning", nullable = false)
    @Builder.Default
    private boolean acknowledgedWarning = false;

    @Column(name = "token_version", nullable = false)
    @Builder.Default
    private long tokenVersion = 0L;
}
