package com.picsou.repository;

import com.picsou.model.AppUser;
import com.picsou.model.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByUsername(String username);
    boolean existsByUsername(String username);

    long countByRole(UserRole role);

    @Query("SELECT u FROM AppUser u JOIN FETCH u.member WHERE u.id = :id")
    Optional<AppUser> findByIdWithMember(Long id);

    @Query("SELECT u FROM AppUser u JOIN FETCH u.member WHERE u.username = :username")
    Optional<AppUser> findByUsernameWithMember(String username);

    Optional<AppUser> findByActivationToken(String token);

    Optional<AppUser> findByMemberId(Long memberId);
}
