package com.picsou.repository;

import com.picsou.model.SharedResource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SharedResourceRepository extends JpaRepository<SharedResource, Long> {
    List<SharedResource> findAllByOwnerMemberIdAndResourceType(Long ownerMemberId, String resourceType);
    boolean existsByOwnerMemberIdAndResourceTypeAndResourceId(Long ownerMemberId, String resourceType, Long resourceId);
    void deleteAllByOwnerMemberIdAndResourceType(Long ownerMemberId, String resourceType);
}
