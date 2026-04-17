package com.picsou.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "sharing_settings", uniqueConstraints = @UniqueConstraint(columnNames = {"member_id", "resource_type"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SharingSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private FamilyMember member;

    @Column(name = "resource_type", nullable = false, length = 20)
    private String resourceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "sharing_level", nullable = false)
    @Builder.Default
    private SharingLevel sharingLevel = SharingLevel.NONE;
}
