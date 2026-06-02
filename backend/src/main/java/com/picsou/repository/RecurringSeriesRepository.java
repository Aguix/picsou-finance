package com.picsou.repository;

import com.picsou.model.RecurringSeries;
import com.picsou.model.RecurringStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface RecurringSeriesRepository extends JpaRepository<RecurringSeries, Long> {

    List<RecurringSeries> findAllByMemberIdOrderByNextDueDateAsc(Long memberId);

    List<RecurringSeries> findAllByMemberIdAndStatusOrderByNextDueDateAsc(Long memberId, RecurringStatus status);

    Optional<RecurringSeries> findByIdAndMemberId(Long id, Long memberId);

    /** Detection key: re-find an existing series for the same counterparty (any status). */
    Optional<RecurringSeries> findByMemberIdAndCounterpartyIgnoreCase(Long memberId, String counterparty);

    /** Confirmed series whose projected due date has fallen within the calendar window. */
    List<RecurringSeries> findAllByMemberIdAndStatusAndNextDueDateLessThanEqualOrderByNextDueDateAsc(
        Long memberId, RecurringStatus status, LocalDate through);
}
