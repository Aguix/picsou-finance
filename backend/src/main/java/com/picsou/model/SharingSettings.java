package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "sharing_settings", uniqueConstraints = @UniqueConstraint(columnNames = {"member_id", "resource_type"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SharingSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private FamilyMember member;

    @Column(name = "resource_type", nullable = false, length = 20)
    private String resourceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "sharing_level", nullable = false, columnDefinition = "sharing_level")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.NAMED_ENUM)
    @Builder.Default
    private SharingLevel sharingLevel = SharingLevel.NONE;
}
