package com.picsou.repository;

import com.picsou.model.Aggregator;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AggregatorRepository extends JpaRepository<Aggregator, Long> {

    /** Look up an aggregator by the stable key that matches {@code PriceProviderPort.aggregatorKey()}. */
    Optional<Aggregator> findByAggregatorKey(String aggregatorKey);
}
