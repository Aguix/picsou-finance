package com.picsou.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "finary_session")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FinarySession extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private FamilyMember member;

    @Column(nullable = false, length = 500)
    private String email;

    @Column(nullable = false, length = 500)
    private String password;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "CONNECTED";

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;
}
