package com.picsou.repository;

import com.picsou.model.Transaction;
import com.picsou.model.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    List<Transaction> findByAccountIdOrderByDateDesc(Long accountId);

    Optional<Transaction> findByIdAndAccountId(Long id, Long accountId);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t WHERE t.account.id = :accountId")
    BigDecimal sumAmountByAccountId(@Param("accountId") Long accountId);

    void deleteByAccountId(Long accountId);

    void deleteByAccountIdAndIsManualFalse(Long accountId);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t WHERE t.account.id = :accountId AND t.date > :date")
    BigDecimal sumAmountByAccountIdAndDateAfter(@Param("accountId") Long accountId, @Param("date") LocalDate date);

    List<Transaction> findByAccountIdAndTxTypeInOrderByDateAsc(Long accountId, List<TransactionType> types);

    /** Same as above but across several accounts at once — used by the consolidated crypto view. */
    List<Transaction> findByAccountIdInAndTxTypeInOrderByDateAsc(
        List<Long> accountIds, List<TransactionType> types);

    /** Earliest transaction date across all accounts */
    @Query("SELECT MIN(t.date) FROM Transaction t")
    LocalDate findEarliestDate();

    /** Earliest transaction date per ticker, for the tickers that have at least one transaction. */
    @Query("SELECT t.ticker AS ticker, MIN(t.date) AS earliestDate FROM Transaction t "
        + "WHERE t.ticker IN :tickers GROUP BY t.ticker")
    List<TickerEarliestDate> findEarliestDatesByTickerIn(@Param("tickers") Set<String> tickers);
}
