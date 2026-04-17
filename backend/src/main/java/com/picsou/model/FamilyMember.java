package com.picsou.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "family_member")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FamilyMember extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(name = "avatar_color", nullable = false, length = 7)
    @Builder.Default
    private String avatarColor = "#6366f1";

    @Column(name = "is_managed", nullable = false)
    @Builder.Default
    private boolean managed = false;
}
