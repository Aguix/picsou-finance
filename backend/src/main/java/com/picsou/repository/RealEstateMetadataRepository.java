package com.picsou.repository;

import com.picsou.model.RealEstateMetadata;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RealEstateMetadataRepository extends JpaRepository<RealEstateMetadata, Long> {
    Optional<RealEstateMetadata> findByAccountId(Long accountId);
}
