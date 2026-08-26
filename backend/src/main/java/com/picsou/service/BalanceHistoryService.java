package com.picsou.service;

import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.BalanceSnapshot;
import com.picsou.model.FinancialAsset;
import com.picsou.model.PriceSnapshot;
import com.picsou.model.Transaction;
import com.picsou.model.TransactionType;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.BalanceSnapshotRepository;
import com.picsou.repository.FinancialAssetRepository;
import com.picsou.repository.PriceSnapshotRepository;
import com.picsou.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Rebuilds an account's daily {@code balance_snapshot} curve from its BUY/SELL/REWARD transaction
 * timeline and the current {@code price_snapshot} history — the write side of the value history the
 * dashboard reads back through {@link HistoryService}.
 *
 * <p>Two callers, one algorithm ({@link #rebuild}):
 * <ul>
 *   <li>the CSV import ({@link com.picsou.crypto.CryptoImportService}), which hands the freshly
 *       parsed rows so the curve reaches back to the first transaction the moment the import
 *       commits;</li>
 *   <li>a mapping change ({@link FinancialAssetService#applyMappings} /
 *       {@link FinancialAssetService#clearMapping} / {@link FinancialAssetService#markWorthless}),
 *       which re-prices an asset's {@code price_snapshot} history — leaving every holding account's
 *       stored {@code balance_snapshot} rows valued under the <em>old</em> id until they're rebuilt
 *       here. That rebuild sources the account's <em>stored</em> transactions
 *       ({@link #rebuildFromTransactions}).</li>
 * </ul>
 *
 * <p>A snapshot row is an account's <b>total</b> value on a day, so rebuilding one asset's price
 * still means replaying the whole account: every ticker's held quantity is priced at each day and
 * summed. That's only faithful for an account whose value is fully described by its trade timeline,
 * so {@link #rebuildFromTransactions} self-gates on the replay reproducing the account's current
 * holdings and leaves balance-synced accounts (bank/exchange/wallet) untouched — see
 * {@link #timelineReproducesHoldings}.
 *
 * <p>Idempotent: an existing snapshot for a day is updated in place (the table is unique on
 * account+date), so a re-run refreshes the curve rather than duplicating it. Best-effort: a failure
 * is logged and swallowed so it never rolls back the import or the mapping change that triggered it.
 */
@Service
@RequiredArgsConstructor
public class BalanceHistoryService {

    private static final Logger log = LoggerFactory.getLogger(BalanceHistoryService.class);

    private final TransactionRepository transactionRepository;
    private final AccountHoldingRepository accountHoldingRepository;
    private final BalanceSnapshotRepository balanceSnapshotRepository;
    private final PriceSnapshotRepository priceSnapshotRepository;
    private final FinancialAssetRepository assetRepository;

    /**
     * One quantity-bearing transaction leg, normalized from either a parsed import row or a stored
     * {@link Transaction}. {@code amount} is the row's (signed) EUR value — only its magnitude is
     * used, to accumulate invested capital on BUY and release it on SELL.
     */
    public record HistoryLeg(
        LocalDate date,
        String ticker,
        TransactionType txType,
        BigDecimal quantity,
        BigDecimal amount
    ) {}

    /**
     * Rebuild an account's daily {@code balance_snapshot} rows from its <em>stored</em>
     * BUY/SELL/REWARD transactions and the current price history — the entry point after a mapping
     * change re-prices an asset the account holds.
     *
     * <p><b>Self-gating.</b> The rebuild replays the trade timeline to derive each day's held
     * quantity and overwrites the whole account curve, so it's only faithful when the account's
     * value is <em>fully</em> described by that timeline (a CSV import, or manually-entered trades).
     * A balance-synced account (bank/exchange/wallet) carries its positions as holdings with no
     * BUY/SELL/REWARD rows — replaying it would drop those positions into a partial curve, worse than
     * the stale valuation this fixes. So the account is rebuilt only when the replay reproduces its
     * current holdings ({@link #timelineReproducesHoldings}); otherwise it's left untouched. This
     * gate — not the account's type — is what makes rebuilding <em>every</em> holder safe.
     */
    @Transactional
    public void rebuildFromTransactions(Account account) {
        List<HistoryLeg> legs = transactionRepository
            .findByAccountIdAndTxTypeInOrderByDateAscIdAsc(account.getId(),
                List.of(TransactionType.BUY, TransactionType.SELL, TransactionType.REWARD))
            .stream()
            .filter(t -> t.getTicker() != null && !t.getTicker().isBlank())
            .map(t -> new HistoryLeg(t.getDate(), t.getTicker(), t.getTxType(),
                t.getQuantity(), t.getAmount()))
            .toList();
        if (!timelineReproducesHoldings(account, legs)) {
            log.info("Skipping value-history rebuild for account {} — its holdings aren't fully "
                + "reproduced by its BUY/SELL/REWARD timeline (balance-synced positions); "
                + "snapshots left intact", account.getId());
            return;
        }
        rebuild(account, legs);
    }

    /**
     * True when replaying the legs yields exactly the account's current holdings — quantity by
     * quantity, mirroring {@link HoldingComputeService} (a net position ≤ 0 is no holding). That's
     * the signal the account is entirely trade-derived and safe to overwrite. A single held ticker
     * absent from the timeline (a balance-synced position), or a quantity that doesn't match, fails
     * the check and the account keeps its stored curve.
     */
    private boolean timelineReproducesHoldings(Account account, List<HistoryLeg> legs) {
        Map<String, BigDecimal> net = new HashMap<>();
        for (HistoryLeg t : legs) {
            if (t.ticker() == null || t.txType() == null) continue;
            BigDecimal q = t.quantity() != null ? t.quantity() : BigDecimal.ZERO;
            net.merge(t.ticker().toUpperCase(),
                t.txType() == TransactionType.SELL ? q.negate() : q, BigDecimal::add);
        }
        Map<String, BigDecimal> replayed = new HashMap<>();
        net.forEach((tk, q) -> { if (q.signum() > 0) replayed.put(tk, q); });

        Map<String, BigDecimal> current = new HashMap<>();
        for (AccountHolding h : accountHoldingRepository.findByAccount_Id(account.getId())) {
            current.put(h.getAsset().getSymbol().toUpperCase(), h.getQuantity());
        }

        if (!replayed.keySet().equals(current.keySet())) return false;
        for (Map.Entry<String, BigDecimal> e : current.entrySet()) {
            BigDecimal r = replayed.get(e.getKey());
            if (r == null || e.getValue() == null || r.compareTo(e.getValue()) != 0) return false;
        }
        return true;
    }

    /**
     * Rebuild daily {@link BalanceSnapshot}s for the account from the given transaction timeline and
     * the (backfilled) daily price history, so the account's value curve covers its whole life
     * instead of starting at today. For each day: balance = Σ heldQty(ticker, day) × price(ticker,
     * day), forward-filling the most recent known price; invested tracks BUY capital in and SELL
     * capital out. Best-effort — never propagates, so it can't roll back the transaction that drove
     * it.
     */
    @Transactional
    public void rebuild(Account account, List<HistoryLeg> legs) {
        try {
            List<HistoryLeg> sorted = legs.stream()
                .filter(t -> t.date() != null && t.ticker() != null && t.txType() != null)
                .sorted(Comparator.comparing(HistoryLeg::date))
                .toList();
            if (sorted.isEmpty()) {
                return;
            }

            LocalDate start = sorted.get(0).date();
            LocalDate today = LocalDate.now();
            Set<String> tickers = sorted.stream()
                .map(t -> t.ticker().toUpperCase())
                .collect(Collectors.toSet());

            // Price history per ticker as a date→price TreeMap for floor (forward-fill) lookups.
            Map<Long, String> idToSymbol = assetRepository.findBySymbolIn(tickers).stream()
                .collect(Collectors.toMap(FinancialAsset::getId, FinancialAsset::getSymbol));
            Map<String, TreeMap<LocalDate, BigDecimal>> priceHist = new HashMap<>();
            for (PriceSnapshot ps : priceSnapshotRepository.findByAssetIdInAndDateBetween(
                    idToSymbol.keySet(), start, today)) {
                priceHist.computeIfAbsent(idToSymbol.get(ps.getAsset().getId()), k -> new TreeMap<>())
                    .put(ps.getDate(), ps.getPriceEur());
            }
            // Make sure today's live price (already on the holdings) anchors the latest point.
            for (AccountHolding h : accountHoldingRepository.findByAccount_Id(account.getId())) {
                if (h.getCurrentPrice() != null) {
                    priceHist.computeIfAbsent(h.getAsset().getSymbol().toUpperCase(), k -> new TreeMap<>())
                        .putIfAbsent(today, h.getCurrentPrice());
                }
            }

            Map<LocalDate, List<HistoryLeg>> byDate = sorted.stream()
                .collect(Collectors.groupingBy(HistoryLeg::date));
            Map<LocalDate, BalanceSnapshot> existing = balanceSnapshotRepository
                .findByAccountIdOrderByDateAsc(account.getId()).stream()
                .collect(Collectors.toMap(BalanceSnapshot::getDate, s -> s, (a, b) -> a, HashMap::new));

            Map<String, BigDecimal> qty = new HashMap<>();
            BigDecimal invested = BigDecimal.ZERO;
            List<BalanceSnapshot> toSave = new ArrayList<>();

            for (LocalDate day = start; !day.isAfter(today); day = day.plusDays(1)) {
                List<HistoryLeg> dayTxs = byDate.get(day);
                if (dayTxs != null) {
                    for (HistoryLeg t : dayTxs) {
                        BigDecimal q = t.quantity() != null ? t.quantity() : BigDecimal.ZERO;
                        String tk = t.ticker().toUpperCase();
                        if (t.txType() == TransactionType.SELL) {
                            qty.merge(tk, q.negate(), BigDecimal::add);
                            invested = invested.subtract(t.amount() != null ? t.amount().abs() : BigDecimal.ZERO);
                        } else { // BUY or REWARD both add quantity; only BUY adds invested capital
                            qty.merge(tk, q, BigDecimal::add);
                            if (t.txType() == TransactionType.BUY) {
                                invested = invested.add(t.amount() != null ? t.amount().abs() : BigDecimal.ZERO);
                            }
                        }
                    }
                }

                BigDecimal value = BigDecimal.ZERO;
                for (Map.Entry<String, BigDecimal> e : qty.entrySet()) {
                    if (e.getValue().signum() <= 0) {
                        continue;
                    }
                    TreeMap<LocalDate, BigDecimal> ph = priceHist.get(e.getKey());
                    if (ph == null) {
                        continue;
                    }
                    Map.Entry<LocalDate, BigDecimal> priced = ph.floorEntry(day);
                    if (priced != null) {
                        value = value.add(e.getValue().multiply(priced.getValue()));
                    }
                }

                LocalDate d = day;
                BalanceSnapshot snap = existing.computeIfAbsent(d, k ->
                    BalanceSnapshot.builder().account(account).date(k).build());
                snap.setBalance(value);
                snap.setInvestedAmount(invested.max(BigDecimal.ZERO));
                toSave.add(snap);
            }

            balanceSnapshotRepository.saveAll(toSave);
            log.info("Rebuilt {} daily snapshots for account {} ({} → {})",
                toSave.size(), account.getId(), start, today);
        } catch (Exception e) {
            log.warn("History reconstruction failed for account {}: {}",
                account.getId(), e.getMessage());
        }
    }
}
