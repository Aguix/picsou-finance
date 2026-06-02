package com.picsou.service.budget;

import com.picsou.model.RecurringCadence;
import com.picsou.model.RecurringSeries;
import com.picsou.model.RecurringStatus;
import com.picsou.model.Transaction;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.RecurringSeriesRepository;
import com.picsou.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finds recurring cash movements (subscriptions, direct debits, salaries) by looking for a
 * counterparty that bills a <em>stable amount</em> at a <em>regular interval</em>. Runs after
 * each sync and on demand.
 *
 * <p>The heuristic, deliberately conservative to avoid false positives:
 * <ol>
 *   <li>Group a member's recent transactions by normalised counterparty.</li>
 *   <li>Keep groups with at least {@value #MIN_OCCURRENCES} occurrences.</li>
 *   <li>Require the gaps between consecutive dates to be regular and to map onto a known
 *       {@link RecurringCadence} (see {@link RecurringCadence#fromMedianDays}).</li>
 *   <li>Require the amounts to stay within {@value #AMOUNT_TOLERANCE_PCT}% of their median.</li>
 * </ol>
 * Matches upsert a {@link RecurringStatus#SUGGESTED} series; a counterparty the user has
 * {@link RecurringStatus#IGNORED} is never resurrected, and a {@link RecurringStatus#CONFIRMED}
 * one only has its amount/dates refreshed.
 */
@Service
@Transactional(readOnly = true)
public class RecurringDetectionService {

    /** Detection needs at least this many occurrences to call something recurring. */
    static final int MIN_OCCURRENCES = 3;
    /** Per-transaction amount may deviate at most this far from the group median. */
    static final int AMOUNT_TOLERANCE_PCT = 15;
    /** Each gap must be within this fraction of the median gap to count as regular. */
    static final double INTERVAL_TOLERANCE = 0.30;
    /** How far back to look — a year covers monthly/quarterly comfortably. */
    static final int LOOKBACK_DAYS = 365;

    private final TransactionRepository transactionRepository;
    private final RecurringSeriesRepository seriesRepository;
    private final FamilyMemberRepository familyMemberRepository;

    public RecurringDetectionService(
        TransactionRepository transactionRepository,
        RecurringSeriesRepository seriesRepository,
        FamilyMemberRepository familyMemberRepository
    ) {
        this.transactionRepository = transactionRepository;
        this.seriesRepository = seriesRepository;
        this.familyMemberRepository = familyMemberRepository;
    }

    /**
     * Scan the member's recent transactions and upsert detected series. Returns the number of
     * series created or refreshed. Idempotent: re-running over the same data changes nothing
     * beyond keeping {@code lastSeenDate}/{@code nextDueDate} current.
     */
    @Transactional
    public int detect(Long memberId, LocalDate today) {
        LocalDate from = today.minusDays(LOOKBACK_DAYS);
        List<Transaction> transactions = transactionRepository
            .findByMemberIdAndDateBetween(memberId, from, today);

        Map<String, List<Transaction>> byCounterparty = groupByCounterparty(transactions);

        int upserted = 0;
        for (Map.Entry<String, List<Transaction>> group : byCounterparty.entrySet()) {
            Candidate candidate = analyse(group.getValue());
            if (candidate != null && upsert(memberId, group.getKey(), candidate)) {
                upserted++;
            }
        }
        return upserted;
    }

    // ─── Grouping ─────────────────────────────────────────────────────────────

    /** Group transactions by normalised counterparty, preserving date order within groups. */
    static Map<String, List<Transaction>> groupByCounterparty(List<Transaction> transactions) {
        Map<String, List<Transaction>> groups = new LinkedHashMap<>();
        for (Transaction tx : transactions) {
            String key = normalise(tx.getCounterparty());
            if (key.isEmpty()) {
                continue;
            }
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(tx);
        }
        groups.values().forEach(list -> list.sort(Comparator.comparing(Transaction::getDate)
            .thenComparing(Transaction::getId)));
        return groups;
    }

    /** Lowercase, trim, and collapse internal whitespace so "NETFLIX  " == "Netflix". */
    static String normalise(String counterparty) {
        if (counterparty == null) {
            return "";
        }
        return counterparty.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    // ─── Analysis ─────────────────────────────────────────────────────────────

    /** A confirmed-regular group, carrying everything needed to upsert a series. */
    record Candidate(RecurringCadence cadence, BigDecimal expectedAmount,
                     LocalDate lastSeen, LocalDate nextDue, String label) {}

    /**
     * Decide whether a single counterparty's transactions form a regular series. Returns a
     * {@link Candidate} when they do, or {@code null} otherwise (too few, irregular gaps, or
     * unstable amounts).
     */
    static Candidate analyse(List<Transaction> txs) {
        if (txs.size() < MIN_OCCURRENCES) {
            return null;
        }

        List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < txs.size(); i++) {
            gaps.add(ChronoUnit.DAYS.between(txs.get(i - 1).getDate(), txs.get(i).getDate()));
        }
        double medianGap = median(gaps.stream().map(Long::doubleValue).toList());
        if (medianGap <= 0 || !gapsAreRegular(gaps, medianGap)) {
            return null;
        }

        RecurringCadence cadence = RecurringCadence.fromMedianDays(medianGap);
        if (cadence == null) {
            return null;
        }

        List<BigDecimal> amounts = txs.stream().map(Transaction::getAmount).toList();
        if (!amountsAreStable(amounts)) {
            return null;
        }

        Transaction last = txs.get(txs.size() - 1);
        BigDecimal expected = medianAmount(amounts);
        String label = last.getCounterparty().trim();
        return new Candidate(cadence, expected, last.getDate(), cadence.next(last.getDate()), label);
    }

    /** Every gap must sit within {@link #INTERVAL_TOLERANCE} of the median gap. */
    static boolean gapsAreRegular(List<Long> gaps, double medianGap) {
        double allowed = medianGap * INTERVAL_TOLERANCE;
        return gaps.stream().allMatch(g -> Math.abs(g - medianGap) <= allowed);
    }

    /** Amounts (by magnitude) must stay within {@link #AMOUNT_TOLERANCE_PCT}% of their median. */
    static boolean amountsAreStable(List<BigDecimal> amounts) {
        BigDecimal median = medianAmount(amounts).abs();
        if (median.signum() == 0) {
            return false;
        }
        BigDecimal tolerance = median.multiply(BigDecimal.valueOf(AMOUNT_TOLERANCE_PCT))
            .divide(BigDecimal.valueOf(100));
        return amounts.stream()
            .allMatch(a -> a.abs().subtract(median).abs().compareTo(tolerance) <= 0);
    }

    static BigDecimal medianAmount(List<BigDecimal> amounts) {
        List<BigDecimal> sorted = amounts.stream().sorted().toList();
        int mid = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(mid);
        }
        return sorted.get(mid - 1).add(sorted.get(mid))
            .divide(BigDecimal.valueOf(2));
    }

    static double median(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        if (sorted.isEmpty()) {
            return 0;
        }
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 1
            ? sorted.get(mid)
            : (sorted.get(mid - 1) + sorted.get(mid)) / 2.0;
    }

    // ─── Upsert ───────────────────────────────────────────────────────────────

    /**
     * Create or refresh the series for this counterparty. Returns true if a row was written.
     * IGNORED series are left untouched (the user dismissed them); CONFIRMED and SUGGESTED
     * ones get their amount, cadence and dates refreshed from the latest data.
     */
    private boolean upsert(Long memberId, String normalisedCounterparty, Candidate candidate) {
        RecurringSeries series = seriesRepository
            .findByMemberIdAndCounterpartyIgnoreCase(memberId, candidate.label())
            .orElse(null);

        if (series != null && series.getStatus() == RecurringStatus.IGNORED) {
            return false;
        }

        if (series == null) {
            series = RecurringSeries.builder()
                .member(familyMemberRepository.getReferenceById(memberId))
                .counterparty(candidate.label())
                .label(candidate.label())
                .status(RecurringStatus.SUGGESTED)
                .build();
        }
        series.setExpectedAmount(candidate.expectedAmount());
        series.setCadence(candidate.cadence());
        series.setLastSeenDate(candidate.lastSeen());
        series.setNextDueDate(candidate.nextDue());
        seriesRepository.save(series);
        return true;
    }
}
