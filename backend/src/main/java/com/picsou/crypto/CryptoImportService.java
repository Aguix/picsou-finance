package com.picsou.crypto;

import com.picsou.dto.AccountResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.model.FinancialAsset;
import com.picsou.model.PriceSnapshot;
import com.picsou.model.Transaction;
import com.picsou.model.TransactionType;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.FinancialAssetRepository;
import com.picsou.repository.PriceSnapshotRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.BalanceHistoryService;
import com.picsou.service.FinancialAssetService;
import com.picsou.service.HoldingComputeService;
import com.picsou.service.PriceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Two-phase multi-exchange CSV import: parse + preview, then commit onto a CRYPTO account.
 *
 * <p>The uploaded file's format is auto-detected against the registered {@link CryptoCsvParser}s,
 * each of which normalizes its rows into the shared {@link ParsedCryptoTx} stream. The flow
 * mirrors the Finary xlsx import (UUID file token, 30-min in-memory cache): committed
 * transactions are non-manual, so a re-import replaces them while preserving manually-added rows;
 * holdings (quantity + diluted average buy-in) are then derived by {@link HoldingComputeService}.
 *
 * <p>Sources whose CSV carries no fiat valuation (crypto-quoted trades, wallet transfers) are
 * <em>enriched</em> at import time: daily price history is backfilled first and each unvalued row
 * is priced at its date. REWARD rows keep a zero price-per-unit regardless — only their EUR
 * income value is enriched — so free coins still dilute the VWAP average buy-in.
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
    private final BalanceHistoryService balanceHistoryService;
    private final FinancialAssetService financialAssetService;
    private final FinancialAssetRepository assetRepository;
    private final TransactionTemplate txTemplate;

    private final ConcurrentHashMap<String, Parsed> cache = new ConcurrentHashMap<>();

    // Per-account handle on the in-flight price backfill/valuation kicked off by execute(). The
    // import returns as soon as the account, transactions and (unpriced) holdings are persisted; the
    // heavy provider work runs on this single daemon thread, and the /pricing endpoint awaits the
    // matching future so the UI can refetch exactly when the prices land.
    private final ExecutorService pricingExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "crypto-import-pricing");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentHashMap<Long, CompletableFuture<Void>> pricingJobs = new ConcurrentHashMap<>();

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
                               BalanceHistoryService balanceHistoryService,
                               FinancialAssetService financialAssetService,
                               FinancialAssetRepository assetRepository,
                               TransactionTemplate txTemplate) {
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
        this.balanceHistoryService = balanceHistoryService;
        this.financialAssetService = financialAssetService;
        this.assetRepository = assetRepository;
        this.txTemplate = txTemplate;
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

        // Resolve tickers *provisionally* (nothing persisted) and hand the UI a confirm/correct
        // choice per coin that isn't already settled — one block per available aggregator, each with
        // its own guess and candidates. This is what lets the operator catch a silent AUTO mis-match
        // before the import commits, and pick an id on more than one aggregator so the price survives
        // one of them being down. The confirmed choices come back on the import request and are
        // applied as USER by execute() before the price backfill.
        List<ImportAssetChoice> assetChoices = financialAssetService
            .previewResolutions(importedTickers(txs)).stream()
            .map(p -> new ImportAssetChoice(
                p.symbol(),
                p.currentStatus() != null ? p.currentStatus().name() : null,
                p.aggregators().stream()
                    .map(a -> new ImportAssetChoice.AggregatorBlock(
                        a.aggregatorKey(),
                        a.suggested() != null ? a.suggested().id() : null,
                        a.candidates().stream()
                            .map(c -> new ImportAssetChoice.Candidate(
                                c.id(), c.name(), c.symbol(), c.marketCapRank()))
                            .toList()))
                    .toList()))
            .toList();

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
            totalInvested, totalRewards, rewardsByKind, assetChoices, existing);
    }

    /**
     * Commit the import in two phases so the UI never waits on the network. Synchronously (this
     * transaction) the account, its transactions and holdings are persisted — the holdings show up
     * immediately, only unpriced. The mappings, price backfill, valuation, live refresh and history
     * rebuild — every step that hits a provider — run on a background thread in their own transaction
     * ({@link #finishImportPricing}); the {@code /pricing} endpoint awaits that job so the client can
     * refetch exactly when the prices land. The preview stays synchronous (its candidates drive the
     * validation UI); only {@code execute}'s pricing is backgrounded.
     */
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

        // Persist the rows and derive positions right away, so the account shows its holdings the
        // instant the import returns. These are the *raw* rows: any that the CSV left unvalued
        // (crypto-quoted trades, wallet transfers) carry no EUR amount yet — the background job below
        // re-values them and recomputes the VWAP once the price history is in. Replace previously
        // imported (non-manual) rows; keep manual entries.
        transactionRepository.deleteByAccountIdAndIsManualFalse(account.getId());
        List<Transaction> toInsert = buildTransactions(account, parsed.transactions, parser);
        transactionRepository.saveAll(toInsert);
        holdingComputeService.recomputeHoldings(account);
        account.setLastSyncedAt(Instant.now());
        accountRepository.save(account);

        cache.remove(req.fileToken());

        int holdingsCount = accountHoldingRepository.findByAccount_Id(account.getId()).size();
        BigDecimal totalRewards = sumAbs(parsed.transactions, TransactionType.REWARD);

        // Hand the coin mappings + parsed rows to the background pricing job. Captured directly (not
        // via the cache, which is already cleared) so the closure owns them.
        Long accountId = account.getId();
        List<ImportAssetMapping> mappings = req.assetMappings();
        List<ParsedCryptoTx> rows = parsed.transactions;
        startPricingJob(accountId, () -> finishImportPricing(accountId, mappings, rows, parser));

        return new CryptoImportResult(
            accountId, account.getName(), parser.sourceId(),
            toInsert.size(), holdingsCount, totalRewards);
    }

    /** Map parsed rows onto persistable transactions — shared by the synchronous raw insert and the
     *  background re-insert of the valued rows. */
    private List<Transaction> buildTransactions(Account account, List<ParsedCryptoTx> rows, CryptoCsvParser parser) {
        return rows.stream()
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
    }

    /**
     * The background half of {@link #execute}: everything that talks to a price provider. Runs in its
     * own transaction on {@link #pricingExecutor}. Applies the confirmed coin mappings (so prices are
     * fetched under the right ids), backfills daily history, re-values the rows the CSV left unpriced,
     * replaces the raw rows persisted synchronously with the valued ones, recomputes the VWAP holdings,
     * values the account live, and rebuilds its daily value curve. Best-effort — any failure leaves the
     * synchronously-imported account/holdings intact (just unpriced) and is surfaced by the job future.
     */
    void finishImportPricing(Long accountId, List<ImportAssetMapping> mappings,
                             List<ParsedCryptoTx> rows, CryptoCsvParser parser) {
        Account account = accountRepository.findById(accountId).orElse(null);
        if (account == null) {
            log.warn("Crypto import pricing: account {} no longer exists — skipping", accountId);
            return;
        }

        // Apply the operator's confirmed coin mappings as USER *before* the backfill, so prices are
        // fetched under the right id — no silent AUTO guess, no re-import needed to correct one.
        applyConfirmedMappings(mappings);

        // Backfill daily price history first: the valuation below and the cost-vs-price overlay on the
        // stats page both need it. Best-effort — a provider hiccup must not fail the whole job.
        backfillPriceHistory(rows);

        // Value the rows whose CSV carried no fiat amount from the backfilled daily prices, then
        // replace the raw rows persisted synchronously with the valued ones and re-derive holdings.
        List<ParsedCryptoTx> enriched = enrichValuations(rows);
        transactionRepository.deleteByAccountIdAndIsManualFalse(accountId);
        transactionRepository.saveAll(buildTransactions(account, enriched, parser));
        holdingComputeService.recomputeHoldings(account);

        // Value the account now so it isn't 0 until the next scheduled price refresh.
        BigDecimal balanceEur = valueHoldings(accountId);
        account.setCurrentBalance(balanceEur);
        account.setLastSyncedAt(Instant.now());
        accountRepository.save(account);

        // Rebuild the account's daily value history from the transaction timeline × backfilled prices,
        // so the portfolio curve goes back to the first transaction instead of today.
        reconstructHistory(account, enriched);
        log.info("Crypto import pricing complete for account {} — {} EUR", accountId, balanceEur);
    }

    /**
     * Register the pricing job's future under {@code accountId} synchronously (so the {@code /pricing}
     * endpoint finds it even if the client awaits the instant the import returns), but dispatch the
     * actual work only <em>after this import's transaction commits</em> — otherwise the background
     * transaction could read the account before it's committed and bail. The work runs in its own
     * transaction on {@link #pricingExecutor}; the future is completed either way (a failure leaves the
     * account unpriced rather than hanging the awaiter), so the reaper can collect it. A prior job for
     * the same account is superseded by a re-import.
     */
    private void startPricingJob(Long accountId, Runnable work) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        pricingJobs.put(accountId, future);

        Runnable dispatch = () -> pricingExecutor.submit(() -> {
            try {
                txTemplate.executeWithoutResult(status -> work.run());
            } catch (Throwable ex) {
                log.error("Crypto import background pricing failed for account {}: {}",
                    accountId, ex.getMessage(), ex);
            } finally {
                future.complete(null);
            }
        });

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    dispatch.run();
                }
                @Override public void afterCompletion(int status) {
                    // Rolled back → the account was never persisted; drop the job and release awaiters.
                    if (status != STATUS_COMMITTED) {
                        pricingJobs.remove(accountId, future);
                        future.complete(null);
                    }
                }
            });
        } else {
            dispatch.run();
        }
    }

    /**
     * The in-flight pricing job for an account, or {@code null} if none is pending (never started, or
     * already finished and reaped). A {@code null} means "nothing to wait for" — treat as done.
     */
    CompletableFuture<Void> pricingFuture(Long accountId) {
        return pricingJobs.get(accountId);
    }

    /**
     * The pricing job to await for a member's account — after checking they own it. Returns
     * {@code null} when no job is pending (already priced, or none ran); the endpoint resolves that
     * immediately. Read-only: it only reads the ownership row and an in-memory future.
     */
    @Transactional(readOnly = true)
    public CompletableFuture<Void> pricingFuture(Long accountId, Long memberId) {
        accountRepository.findByIdAndMemberId(accountId, memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Account not found"));
        return pricingJobs.get(accountId);
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

        Map<Long, String> idToSymbol = assetRepository.findBySymbolIn(tickers).stream()
            .collect(Collectors.toMap(FinancialAsset::getId, FinancialAsset::getSymbol));
        Map<String, TreeMap<LocalDate, BigDecimal>> priceHist = new HashMap<>();
        for (PriceSnapshot ps : priceSnapshotRepository.findByAssetIdInAndDateBetween(
                idToSymbol.keySet(), from, LocalDate.now())) {
            priceHist.computeIfAbsent(idToSymbol.get(ps.getAsset().getId()), k -> new TreeMap<>())
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
        Set<String> tickers = holdings.stream()
            .map(h -> h.getAsset().getSymbol())
            .collect(Collectors.toSet());
        Map<String, BigDecimal> prices = priceService.refreshPrices(tickers);

        BigDecimal total = BigDecimal.ZERO;
        for (AccountHolding h : holdings) {
            BigDecimal price = prices.get(h.getAsset().getSymbol().toUpperCase());
            if (price != null) {
                h.setCurrentPrice(price);
                h.setLastSyncedAt(Instant.now());
                total = total.add(h.getQuantity().multiply(price));
            }
        }
        accountHoldingRepository.saveAll(holdings);
        return total;
    }

    /** The distinct, uppercase, sorted crypto tickers carried by the parsed transactions. */
    private static Set<String> importedTickers(List<ParsedCryptoTx> transactions) {
        return transactions.stream()
            .filter(t -> t.txType() != null && t.ticker() != null && !t.ticker().isBlank())
            .map(t -> t.ticker().toUpperCase())
            .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * Apply the operator's confirmed per-coin decisions from the import preview: {@code MAP} pins the
     * picked id of every aggregator they chose one for as a {@code USER} mapping, {@code WORTHLESS}
     * pins a zero, anything else (incl. {@code IGNORE}) leaves the coin unresolved to import unpriced.
     * Best-effort per coin — a single bad mapping is logged and skipped, never failing the import.
     */
    private void applyConfirmedMappings(List<ImportAssetMapping> mappings) {
        if (mappings == null) return;
        for (ImportAssetMapping m : mappings) {
            if (m == null || m.symbol() == null || m.symbol().isBlank()) continue;
            String action = m.action() == null ? "" : m.action().trim().toUpperCase();
            try {
                switch (action) {
                    case "MAP" -> {
                        Map<String, String> ids = new LinkedHashMap<>();
                        if (m.aggregatorIds() != null) ids.putAll(m.aggregatorIds());
                        String name = m.name();
                        // A pasted link is resolved by whichever aggregator recognises it, validated,
                        // and outranks a picked candidate for that same aggregator — the explicit
                        // paste is the stronger signal. A bad link throws and is caught below: that
                        // coin is skipped (imports unpriced), never the whole import.
                        if (m.url() != null && !m.url().isBlank()) {
                            var link = financialAssetService.resolveLink(m.url());
                            ids.put(link.aggregatorKey(), link.candidate().id());
                            if (name == null || name.isBlank()) name = link.candidate().name();
                        }
                        if (!ids.isEmpty()) {
                            financialAssetService.applyMappings(m.symbol(), ids, name);
                        }
                    }
                    case "WORTHLESS" -> financialAssetService.markWorthless(m.symbol());
                    default -> { /* IGNORE — leave unresolved; imports unpriced */ }
                }
            } catch (Exception e) {
                log.warn("Import mapping for {} ({}) failed: {}", m.symbol(), action, e.getMessage());
            }
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
     * Rebuild the account's daily value curve from this import's rows, so it covers the account's
     * whole life instead of starting at import day. Delegates to the shared
     * {@link BalanceHistoryService} (which the mapping-change paths reuse), mapping the parsed rows
     * onto its normalized leg form. Best-effort and idempotent — see that service.
     */
    private void reconstructHistory(Account account, List<ParsedCryptoTx> txs) {
        balanceHistoryService.rebuild(account, txs.stream()
            .map(t -> new BalanceHistoryService.HistoryLeg(
                t.date(), t.ticker(), t.txType(), t.quantity(), t.amount()))
            .toList());
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
        // Reap finished pricing jobs — the /pricing endpoint treats a missing future as "done", so a
        // late await after reaping still resolves immediately.
        pricingJobs.entrySet().removeIf(e -> e.getValue().isDone());
    }
}
