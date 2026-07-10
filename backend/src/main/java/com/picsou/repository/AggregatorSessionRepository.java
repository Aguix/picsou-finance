package com.picsou.repository;

import com.picsou.model.AggregatorSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AggregatorSessionRepository extends JpaRepository<AggregatorSession, Long> {

    /** Every session of an aggregator (by its key), oldest first — for the admin listing. */
    List<AggregatorSession> findByAggregator_AggregatorKeyOrderByIdAsc(String aggregatorKey);

    /** Only the enabled sessions of an aggregator (by its key), oldest first — for the adapters. */
    List<AggregatorSession> findByAggregator_AggregatorKeyAndEnabledTrueOrderByIdAsc(String aggregatorKey);
}
