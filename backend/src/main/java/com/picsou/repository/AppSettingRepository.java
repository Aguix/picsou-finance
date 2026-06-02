package com.picsou.repository;

import com.picsou.model.AppSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AppSettingRepository extends JpaRepository<AppSetting, String> {

    Optional<AppSetting> findByKey(String key);

    /**
     * Compare-and-set update used by the setup wizard to atomically claim the
     * PENDING_ADMIN → IN_PROGRESS transition when two concurrent bootstraps
     * race. Returns 0 if another caller already claimed the state.
     */
    @Modifying
    @Query("UPDATE AppSetting s SET s.value = :newValue WHERE s.key = :key AND s.value = :expectedValue")
    int compareAndSet(@Param("key") String key,
                      @Param("expectedValue") String expectedValue,
                      @Param("newValue") String newValue);
}
