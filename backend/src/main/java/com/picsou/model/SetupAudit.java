package com.picsou.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "setup_audit")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SetupAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String event;

    @Column(name = "actor_username", length = 50)
    private String actorUsername;

    @Column(length = 45)
    private String ip;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(columnDefinition = "TEXT")
    private String details;

    @Column(nullable = false)
    private Instant at;
}
