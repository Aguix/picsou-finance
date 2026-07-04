package com.picsou.crypto;

import com.picsou.dto.AccountResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.BalanceSnapshot;
import com.picsou.model.FamilyMember;
import com.picsou.model.PriceSnapshot;
import com.picsou.model.Transaction;
import com.picsou.model.TransactionType;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.BalanceSnapshotRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.PriceSnapshotRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.CoinMappingService;
import com.picsou.service.HoldingComputeService;
import com.picsou.service.PriceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Two-phase multi-exchange CSV import: parse + preview, then commit onto a CRYPTO account.
 *
 * <p>The uploaded file's format is auto-detected against the registered {@link CryptoCsvParser}s
 * (Crypto.com App/Exchange, Kraken, Binance, Bybit, Bitstack, Ledger Live, generic), each of
 * which normalizes its rows into the shared {@link ParsedCryptoTx} stream. The flow mirrors the
 * Finary xlsx import (UUID file token, 30-min in-memory cache): committed transactions are
 * non-manual, so a re-import replaces them while preserving manually-added rows; holdings
 * (quantity + diluted average buy-in) are then derived by {@link HoldingComputeService}.
 *
 * <p>Sources whose CSV carries no fiat valuation (Kraken rewards, crypto-quoted trades, wallet
 * transfers) are <em>enriched</em> at import time: daily price history is backfilled first and
 * each unvalued row is priced at its date. REWARD rows keep a zero price-per-unit regardless —
 * only their EUR income value is enriched — so free coins still dilute the VWAP average buy-in.
 */
@Service
public class CryptoImportService {

    private static final Logger log = LoggerFactory.getLogger(CryptoImportService.class);

    private final List<CryptoCsvParser> parsers;
    private final AccountRepository accountRepository;
    private final FamilyMemberRepository familyMemberRepository;
    private final TransactionRepository transactionRepository;
    private final AccountHoldingRepository accountHoldingRepository;
    private final HoldingComputeService holdingComputeService;
    private final PriceService priceService;
    private final PriceSnapshotRepository priceSnapshotRepository;
    private final BalanceSnapshotRepository balanceSnapshotRepository;
    private final CoinMappingService coinMappingService;

    private final ConcurrentHashMap<String, Parsed> cache = new ConcurrentHashMap<>();

    private record Parsed(String sourceId, List<ParsedCryptoTx> transactions,
                          String nativeCurrency, Instant parsedAt) {}

    public CryptoImportService(List<CryptoCsvParser> parsers,
                               AccountRepository accountRepository,
                               FamilyMemberRepository familyMemberRepository,
                               TransactionRepository transactionRepository,
                               AccountHoldingRepository accountHoldingRepository,
                               HoldingComputeService holdingComputeService,
                               PriceService priceService,
                               PriceSnapshotRepository priceSnapshotRepository,
                               BalanceSnapshotRepository balanceSnapshotRepository,
                               CoinMappingService coinMappingService) {
        // Generic (permissive) signatures must run after the exchange-specific ones.
        this.parsers = parsers.stream()
            .sorted(Comparator.comparingInt(CryptoCsvParser::detectionOrder))
            .toList();
        this.accountRepository = accountRepository;
        this.familyMemberRepository = familyMemberRepository;
        this.transactionRepository = transactionRepository;
        this.accountHoldingRepository = accountHoldingRepository;
        this.holdingComputeService = holdingComputeService;
        this.priceService = priceService;
        this.priceSnapshotRepository = priceSnapshotRepository;
        this.balanceSnapshotRepository = balanceSnapshotRepository;
        this.coinMappingService = coinMappingService;
    }

    /** The supported source formats, in detection order — for the import UI. */
    public List<CryptoSourceInfo> sources() {
        return parsers.stream().map(p -> new CryptoSourceInfo(p.sourceId(), p.label())).toList();
    }

    public CryptoPreviewResponse preview(MultipartFile file, Long memberId) {
        String csv;
        try {
            csv = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read the uploaded file.", e);
        }

        CsvTable table = CsvTable.parse(csv);
        CryptoCsvParser parser = parsers.stream()
            .filter(p -> p.supports(table))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "Unrecognized CSV format. Supported exports: "
                + parsers.stream().map(CryptoCsvParser::label).collect(Collectors.joining(", "))
                + "."));

        List<ParsedCryptoTx> txs = parser.parse(table);
        if (txs.isEmpty()) {
            throw new IllegalArgumentException(
                "No importable transactions found in this CSV (detected format: "
                + parser.label() + ").");
        }

        String nativeCurrency = txs.stream()
            .map(ParsedCryptoTx::nativeCurrency)
            .filter(c -> c != null && !c.isBlank())
            .findFirst().orElse("EUR");

        String fileToken = UUID.randomUUID().toString();
        cache.put(fileToken, new Parsed(parser.sourceId(), txs, nativeCurrency, Instant.now()));

        int buy = (int) txs.stream().filter(t -> t.txType() == TransactionType.BUY).count();
        int sell = (int) txs.stream().filter(t -> t.txType() == TransactionType.SELL).count();
        int rewardCount = (int) txs.stream().filter(t -> t.txType() == TransactionType.REWARD).count();
        int unknown = (int) txs.stream().filter(t -> t.txType() == null).count();
        int unvalued = (int) txs.stream().filter(ParsedCryptoTx::needsValuation).count();

        Set<String> currencies = txs.stream()
            .map(ParsedCryptoTx::ticker)
            .filter(c -> c != null && !c.isBlank())
            .collect(Collectors.toCollection(TreeSet::new));

        LocalDate first = txs.stream().map(ParsedCryptoTx::date).min(Comparator.naturalOrder()).orElse(null);
        LocalDate last = txs.stream().map(ParsedCryptoTx::date).max(Comparator.naturalOrder()).orElse(null);

        BigDecimal totalInvested = sumAbs(txs, TransactionType.BUY);
        BigDecimal totalRewards = sumAbs(txs, TransactionType.REWARD);

        Map<String, BigDecimal> rewardsByKind = txs.stream()
            .filter(t -> t.txType() == TransactionType.REWARD && t.rewardKind() != null)
            .collect(Collectors.groupingBy(
                t -> t.rewardKind().name(),
                Collectors.reducing(BigDecimal.ZERO, t -> t.amount().abs(), BigDecimal::add)));

        List<AccountResponse> existing = accountRepository.findAllByMemberIdOrderByCreatedAtAsc(memberId).stream()
            .filter(a -> a.getType() == AccountType.CRYPTO)
            .map(a -> AccountResponse.from(a, a.getCurrentBalance()))
            .collect(Collectors.toList());

        return new CryptoPreviewResponse(
            fileToken, parser.sourceId(), parser.label(),
            txs.size(), txs.size(), buy, sell, rewardCount, unknown, unvalued,
            first, last, new ArrayList<>(currencies), nativeCurrency,
            totalInvested, totalRewards, rewardsByKind, existing);
    }

    @Transactional
    public CryptoImportResult execute(CryptoImportRequest req, Long memberId) {
        Parsed parsed = cache.get(req.fileToken());
        if (parsed == null) {
            throw new IllegalArgumentException("Preview expired or invalid — please re-upload the file.");
        }
        CryptoCsvParser parser = parsers.stream()
            .filter(p -> p.sourceId().equals(parsed.sourceId()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Unknown source: " + parsed.sourceId()));

        Account account = resolveAccount(req, memberId, parser);

        // Resolve each imported ticker to a CoinGecko id (cached in coin_mapping) so the price
        // backfill and holding valuation below can find a provider. The import context guarantees
        // these are cryptos, so a symbol shared with a stock ticker can't be mis-routed. Ambiguous
        // or unknown tickers stay unresolved (unpriced) rather than guessed.
        resolveTickers(parsed.transactions);

        // Backfill daily price history for the coins first: the valuation enrichment below and
        // the cost-vs-price overlay on the stats page both need it. Best-effort — a provider
        // hiccup (e.g. CoinGecko rate-limit) must not fail the import.
        backfillPriceHistory(parsed.transactions);

        // Value the rows whose CSV carried no fiat amount (Kraken rewards, crypto-quoted
        // trades, wallet transfers) from the backfilled daily prices.
        List<ParsedCryptoTx> enriched = enrichValuations(parsed.transactions);

        // Replace previously-imported (non-manual) rows; keep manual entries.
        transactionRepository.deleteByAccountIdAndIsManualFalse(account.getId());

        List<Transaction> toInsert = enriched.stream()
            .map(t -> Transaction.builder()
                .account(account)
                .date(t.date())
                .description(truncate(t.description(), 255))
                .amount(t.amount() != null ? t.amount() : BigDecimal.ZERO)
                .type(t.rawKind())
                .category(parser.label())
                .nativeCurrency(t.nativeCurrency())
                .isManual(false)
                .txType(t.txType())
                .rewardKind(t.rewardKind())
                .ticker(t.ticker())
                .name(t.name())
                .quantity(t.quantity())
                .pricePerUnit(t.pricePerUnit())
                .build())
            .collect(Collectors.toList());
        transactionRepository.saveAll(toInsert);

        // Derive positions (quantity + diluted VWAP) from the BUY/SELL/REWARD rows.
        holdingComputeService.recomputeHoldings(account);

        // Value the account now so it isn't 0 until the next scheduled price refresh.
        BigDecimal balanceEur = valueHoldings(account.getId());
        account.setCurrentBalance(balanceEur);
        account.setLastSyncedAt(Instant.now());
        accountRepository.save(account);

        // Rebuild the account's daily value history from the transaction timeline × backfilled
        // prices, so the portfolio curve goes back to the first transaction instead of today.
        reconstructHistory(account, enriched);

        cache.remove(req.fileToken());

        int holdingsCount = accountHoldingRepository.findByAccount_Id(account.getId()).size();
        BigDecimal totalRewards = sumAbs(enriched, TransactionType.REWARD);

        return new CryptoImportResult(
            account.getId(), account.getName(), parser.sourceId(),
            toInsert.size(), holdingsCount, totalRewards);
    }

    private Account resolveAccount(CryptoImportRequest req, Long memberId, CryptoCsvParser parser) {
        if ("MAP_EXISTING".equalsIgnoreCase(req.action())) {
            if (req.targetAccountId() == null) {
                throw new IllegalArgumentException("targetAccountId is required for MAP_EXISTING");
            }
            return accountRepository.findByIdAndMemberId(req.targetAccountId(), memberId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));
        }
        if (!"CREATE_NEW".equalsIgnoreCase(req.action())) {
            throw new IllegalArgumentException("Unknown action: " + req.action());
        }
        FamilyMember member = familyMemberRepository.findById(memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));
        String name = req.accountName() != null && !req.accountName().isBlank()
            ? req.accountName().trim() : parser.label();
        Account account = Account.builder()
            .member(member)
            .name(name)
            .type(AccountType.CRYPTO)
            .provider(parser.provider())
            .currency("EUR")
            .currentBalance(BigDecimal.ZERO)
            .isManual(false)
            .color(req.color() != null && !req.color().isBlank() ? req.color() : "#103f68")
            .externalAccountId("crypto_csv:" + parser.sourceId())
            .build();
        return accountRepository.save(account);
    }

    /**
     * Fill in the EUR value of rows the CSV left unvalued, from the (backfilled) daily price
     * history: {@code value = quantity × price(ticker, date)}, forward-filling the most recent
     * price known at that date. BUY/SELL also get their price-per-unit; REWARD keeps price 0
     * (zero-cost acquisition — the VWAP dilution rule) and only gains its income value.
     * Rows with no usable price stay at 0 and are still imported.
     */
    private List<ParsedCryptoTx> enrichValuations(List<ParsedCryptoTx> txs) {
        Set<String> tickers = txs.stream()
            .filter(ParsedCryptoTx::needsValuation)
            .map(t -> t.ticker().toUpperCase())
            .collect(Collectors.toSet());
        if (tickers.isEmpty()) {
            return txs;
        }
        LocalDate from = txs.stream().map(ParsedCryptoTx::date)
            .filter(d -> d != null).min(Comparator.naturalOrder()).orElse(null);
        if (from == null) {
            return txs;
        }

        Map<String, TreeMap<LocalDate, BigDecimal>> priceHist = new HashMap<>();
        for (PriceSnapshot ps : priceSnapshotRepository.findByTickerInAndDateBetween(
                tickers, from, LocalDate.now())) {
            priceHist.computeIfAbsent(ps.getTicker().toUpperCase(), k -> new TreeMap<>())
                .put(ps.getDate(), ps.getPriceEur());
        }

        int valued = 0;
        List<ParsedCryptoTx> out = new ArrayList<>(txs.size());
        for (ParsedCryptoTx t : txs) {
            if (!t.needsValuation()) {
                out.add(t);
                continue;
            }
            TreeMap<LocalDate, BigDecimal> hist = priceHist.get(t.ticker().toUpperCase());
            Map.Entry<LocalDate, BigDecimal> priced = hist != null ? hist.floorEntry(t.date()) : null;
            if (priced == null) {
                out.add(t);
                continue;
            }
            BigDecimal value = t.quantity().multiply(priced.getValue());
            BigDecimal signed = t.txType() == TransactionType.BUY ? value.negate() : value;
            BigDecimal pricePerUnit = t.txType() == TransactionType.REWARD
                ? BigDecimal.ZERO : priced.getValue();
            out.add(t.withValuation(signed, pricePerUnit));
            valued++;
        }
        if (valued > 0) {
            log.info("Crypto import: valued {} unpriced rows from daily price history", valued);
        }
        return out;
    }

    /** Sum quantity × live EUR price over the account's holdings, persisting each fetched price. */
    private BigDecimal valueHoldings(Long accountId) {
        List<AccountHolding> holdings = accountHoldingRepository.findByAccount_Id(accountId);
        if (holdings.isEmpty()) {
            return BigDecimal.ZERO;
        }
        Set<String> tickers = holdings.stream().map(AccountHolding::getTicker).collect(Collectors.toSet());
        Map<String, BigDecimal> prices = priceService.refreshPrices(tickers);

        BigDecimal total = BigDecimal.ZERO;
        for (AccountHolding h : holdings) {
            BigDecimal price = prices.get(h.getTicker().toUpperCase());
            if (price != null) {
                h.setCurrentPrice(price);
                h.setLastSyncedAt(Instant.now());
                total = total.add(h.getQuantity().multiply(price));
            }
        }
        accountHoldingRepository.saveAll(holdings);
        return total;
    }

    /**
     * Resolve every distinct imported ticker to a CoinGecko id (cached in {@code coin_mapping}) so
     * the downstream price backfill/valuation can find a provider. Best-effort — a failure here
     * just leaves a coin unpriced, it must not fail the import.
     */
    private void resolveTickers(List<ParsedCryptoTx> transactions) {
        Set<String> tickers = transactions.stream()
            .filter(t -> t.txType() != null && t.ticker() != null && !t.ticker().isBlank())
            .map(t -> t.ticker().toUpperCase())
            .collect(Collectors.toSet());
        if (!tickers.isEmpty()) {
            coinMappingService.resolveAll(tickers);
        }
    }

    /** Best-effort historical price backfill for the imported coins, from their first activity date. */
    private void backfillPriceHistory(List<ParsedCryptoTx> transactions) {
        try {
            Set<String> tickers = transactions.stream()
                .filter(t -> t.txType() != null && t.ticker() != null && !t.ticker().isBlank())
                .map(t -> t.ticker().toUpperCase())
                .collect(Collectors.toSet());
            if (tickers.isEmpty()) {
                return;
            }
            LocalDate from = transactions.stream()
                .map(ParsedCryptoTx::date)
                .filter(d -> d != null)
                .min(Comparator.naturalOrder())
                .orElse(LocalDate.now().minusMonths(12));
            priceService.backfillHistoricalPrices(tickers, from);
        } catch (Exception e) {
            log.warn("Crypto import: historical price backfill failed: {}", e.getMessage());
        }
    }

    /**
     * Rebuild daily {@link BalanceSnapshot}s for the account from the transaction timeline and the
     * (backfilled) daily price history, so the account's value curve covers its whole life instead
     * of starting at import day. For each day: balance = Σ heldQty(ticker, day) × price(ticker, day),
     * forward-filling the most recent known price. Best-effort — never fails the import. Re-running
     * an import overwrites the snapshots (unique on account+date), so re-importing once prices are
     * fully backfilled refreshes the whole curve.
     */
    private void reconstructHistory(Account account, List<ParsedCryptoTx> txs) {
        try {
            List<ParsedCryptoTx> sorted = txs.stream()
                .filter(t -> t.date() != null && t.ticker() != null && t.txType() != null)
                .sorted(Comparator.comparing(ParsedCryptoTx::date))
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
            Map<String, TreeMap<LocalDate, BigDecimal>> priceHist = new HashMap<>();
            for (PriceSnapshot ps : priceSnapshotRepository.findByTickerInAndDateBetween(tickers, start, today)) {
                priceHist.computeIfAbsent(ps.getTicker().toUpperCase(), k -> new TreeMap<>())
                    .put(ps.getDate(), ps.getPriceEur());
            }
            // Make sure today's live price (already on the holdings) anchors the latest point.
            for (AccountHolding h : accountHoldingRepository.findByAccount_Id(account.getId())) {
                if (h.getCurrentPrice() != null) {
                    priceHist.computeIfAbsent(h.getTicker().toUpperCase(), k -> new TreeMap<>())
                        .putIfAbsent(today, h.getCurrentPrice());
                }
            }

            Map<LocalDate, List<ParsedCryptoTx>> byDate = sorted.stream()
                .collect(Collectors.groupingBy(ParsedCryptoTx::date));
            Map<LocalDate, BalanceSnapshot> existing = balanceSnapshotRepository
                .findByAccountIdOrderByDateAsc(account.getId()).stream()
                .collect(Collectors.toMap(BalanceSnapshot::getDate, s -> s, (a, b) -> a, HashMap::new));

            Map<String, BigDecimal> qty = new HashMap<>();
            BigDecimal invested = BigDecimal.ZERO;
            List<BalanceSnapshot> toSave = new ArrayList<>();

            for (LocalDate day = start; !day.isAfter(today); day = day.plusDays(1)) {
                List<ParsedCryptoTx> dayTxs = byDate.get(day);
                if (dayTxs != null) {
                    for (ParsedCryptoTx t : dayTxs) {
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
            log.info("Crypto import: rebuilt {} daily snapshots for account {} ({} → {})",
                toSave.size(), account.getId(), start, today);
        } catch (Exception e) {
            log.warn("Crypto import: history reconstruction failed for account {}: {}",
                account.getId(), e.getMessage());
        }
    }

    private static BigDecimal sumAbs(List<ParsedCryptoTx> txs, TransactionType type) {
        return txs.stream()
            .filter(t -> t.txType() == type)
            .map(t -> t.amount() != null ? t.amount().abs() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    void cleanupExpiredCache() {
        Instant cutoff = Instant.now().minusSeconds(1800);
        cache.entrySet().removeIf(e -> e.getValue().parsedAt().isBefore(cutoff));
    }
}
