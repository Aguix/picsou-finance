package com.picsou.repository;

import com.picsou.model.FamilyMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FamilyMemberRepository extends JpaRepository<FamilyMember, Long> {
    List<FamilyMember> findAllByOrderByCreatedAtAsc();
    List<FamilyMember> findByManagedTrue();
    List<FamilyMember> findByManagedFalse();
}
