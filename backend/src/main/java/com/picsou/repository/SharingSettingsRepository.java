package com.picsou.repository;

import com.picsou.model.SharingSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SharingSettingsRepository extends JpaRepository<SharingSettings, Long> {
    Optional<SharingSettings> findByMemberIdAndResourceType(Long memberId, String resourceType);
}
