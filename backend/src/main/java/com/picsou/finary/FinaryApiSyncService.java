package com.picsou.finary;

import com.picsou.config.CryptoEncryption;
import com.picsou.dto.*;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.exception.SyncException;
import com.picsou.exception.TotpRequiredException;
import com.picsou.finary.client.FinaryApiClient;
import com.picsou.finary.dto.FinaryAccountDto;
import com.picsou.finary.dto.FinaryTransactionDto;
import com.picsou.model.Account;
import com.picsou.model.FamilyMember;
import com.picsou.model.FinarySession;
import com.picsou.model.AccountType;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.FinarySessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Orchestrates Finary API sync in two phases:
 * 1. preview(totp) -- authenticate, fetch accounts + transactions, cache, return preview
 * 2. execute(syncToken, mappings) -- apply user mappings, import accounts + transactions
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FinaryApiSyncService {

    private static final List<String> ACCOUNT_CATEGORIES = List.of(
        "checkings", "savings", "investments", "real_estates", "cryptos",
        "fonds_euro", "commodities", "credits", "other_assets", "startups"
    );
    private static final List<String> TRANSACTION_CATEGORIES = List.of(
        "checkings", "savings", "investments", "credits"
    );

    private final FinaryApiClient finaryApiClient;
    private final CryptoEncryption encryption;
    private final AccountRepository accountRepository;
    private final FamilyMemberRepository familyMemberRepository;
    private final FinarySessionRepository finarySessionRepository;
    private final FinaryPersistenceHelper persistenceHelper;

    private final ConcurrentHashMap<String, SyncSessionData> cache = new ConcurrentHashMap<>();

    // ---------------------------------------------------------------------------
    // Connection management
    // ---------------------------------------------------------------------------

    public void login(String email, String password, Long memberId) {
        String encryptedEmail = encryption.encrypt(email);
        String encryptedPassword = encryption.encrypt(password);

        FamilyMember member = familyMemberRepository.findById(memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));

        Optional<FinarySession> existing = finarySessionRepository.findByMemberId(memberId);
        FinarySession session;
        if (existing.isPresent()) {
            session = existing.get();
            session.setEmail(encryptedEmail);
            session.setPassword(encryptedPassword);
            session.setStatus("CONNECTED");
        } else {
            session = FinarySession.builder()
                .member(member)
                .email(encryptedEmail)
                .password(encryptedPassword)
                .status("CONNECTED")
                .build();
        }
        finarySessionRepository.save(session);
        log.info("Finary credentials stored for member {}", memberId);
    }

    public FinaryConnectionStatusResponse getConnectionStatus(Long memberId) {
        Optional<FinarySession> session = finarySessionRepository.findByMemberId(memberId);
        if (session.isEmpty()) {
            return new FinaryConnectionStatusResponse(false, null, null, null, null);
        }
        FinarySession s = session.get();
        String decryptedEmail = encryption.decrypt(s.getEmail());
        String maskedEmail = maskEmail(decryptedEmail);
        return new FinaryConnectionStatusResponse(true, s.getId(), s.getStatus(), s.getLastSyncedAt(), maskedEmail);
    }

    public FinaryCheckTotpResponse checkTotp(Long memberId) {
        FinarySession session = finarySessionRepository.findByMemberId(memberId)
            .orElseThrow(() -> new ResourceNotFoundException("No Finary session for member"));
        String email = encryption.decrypt(session.getEmail());
        String password = encryption.decrypt(session.getPassword());
        String signInId = finaryApiClient.checkTotpRequired(email, password);
        return new FinaryCheckTotpResponse(signInId != null);
    }

    @Transactional
    public void deleteSession(Long memberId) {
        finarySessionRepository.findByMemberId(memberId)
            .ifPresent(s -> {
                finarySessionRepository.delete(s);
                log.info("Finary session deleted for member {}", memberId);
            });
    }

    private String maskEmail(String email) {
        int atIndex = email.indexOf('@');
        if (atIndex <= 1) return "***";
        return email.charAt(0) + "***" + email.substring(atIndex);
    }

    // ---------------------------------------------------------------------------
    // Preview phase
    // ---------------------------------------------------------------------------

    /**
     * Preview phase: authenticate, fetch all accounts + transactions, cache data, return preview.
     */
    public FinaryPreviewResponse preview(String totp, Long memberId) {
        FinarySession session = finarySessionRepository.findByMemberId(memberId)
            .orElseThrow(() -> new SyncException("Finary not connected. Please log in first."));

        String email = encryption.decrypt(session.getEmail());
        String password = encryption.decrypt(session.getPassword());

        try {
            // Authenticate
            log.info("Authenticating to Finary API (preview)");
            String jwt = finaryApiClient.authenticate(email, password, totp);

            // Get organization context
            log.info("Fetching organization context");
            FinaryApiClient.OrgContext ctx = finaryApiClient.fetchOrganizationContext(jwt);

            // Fetch all accounts across categories
            log.info("Fetching accounts from all categories");
            List<FinaryAccountDto> allAccounts = new ArrayList<>();
            Map<String, String> externalIdToCategory = new HashMap<>();

            for (String category : ACCOUNT_CATEGORIES) {
                try {
                    List<FinaryAccountDto> accounts = finaryApiClient.fetchCategoryAccounts(jwt, ctx, category);
                    log.info("Fetched {} accounts from category: {}", accounts.size(), category);
                    for (FinaryAccountDto acc : accounts) {
                        allAccounts.add(acc);
                        externalIdToCategory.put("finary_" + category + "_" + acc.id(), category);
                    }
                } catch (Exception e) {
                    log.error("Failed to fetch accounts from category {}: {}", category, e.getMessage());
                    throw new SyncException("Failed to fetch accounts from " + category, e);
                }
            }

            // Fetch all transactions across categories
            log.info("Fetching transactions from categories");
            Map<String, List<FinaryTransactionDto>> transactionsByCategory = new HashMap<>();
            int totalTx = 0;

            for (String category : TRANSACTION_CATEGORIES) {
                try {
                    List<FinaryTransactionDto> txs = fetchAllTransactionPages(jwt, ctx, category);
                    transactionsByCategory.put(category, txs);
                    totalTx += txs.size();
                    log.info("Fetched {} transactions from category: {}", txs.size(), category);
                } catch (Exception e) {
                    log.error("Failed to fetch transactions from category {}: {}", category, e.getMessage());
                    throw new SyncException("Failed to fetch transactions from " + category, e);
                }
            }

            // Cache everything with a sync token
            String syncToken = UUID.randomUUID().toString();
            SyncSessionData sessionData = new SyncSessionData(
                allAccounts, transactionsByCategory, externalIdToCategory, Instant.now()
            );
            cache.put(syncToken, sessionData);

            // Build preview: count transactions per account
            Map<String, Integer> txCountByAccountId = new HashMap<>();
            for (List<FinaryTransactionDto> txs : transactionsByCategory.values()) {
                for (FinaryTransactionDto tx : txs) {
                    txCountByAccountId.merge(tx.account().id(), 1, Integer::sum);
                }
            }

            List<FinaryAccountPreview> previews = allAccounts.stream()
                .map(acc -> {
                    String category = externalIdToCategory.get("finary_" + findCategoryForAccount(acc, allAccounts, externalIdToCategory) + "_" + acc.id());
                    return new FinaryAccountPreview(
                        acc.name(),
                        acc.institution() != null ? acc.institution().name() : "Finary",
                        findCategoryForAccount(acc, allAccounts, externalIdToCategory),
                        FinaryPersistenceHelper.suggestTypeFromApiCategory(findCategoryForAccount(acc, allAccounts, externalIdToCategory)),
                        acc.balance() != null ? acc.balance() : 0,
                        acc.currency() != null ? acc.currency().code() : "EUR",
                        txCountByAccountId.getOrDefault(acc.id(), 0)
                    );
                })
                .collect(Collectors.toList());

            // Fetch existing Picsou accounts for this member
            List<AccountResponse> existing = accountRepository.findAllByMemberIdOrderByCreatedAtAsc(memberId).stream()
                .map(a -> AccountResponse.from(a, a.getCurrentBalance()))
                .collect(Collectors.toList());

            // Auto-mapping: check if all Finary accounts already have matching Picsou accounts
            boolean allAutoMapped = true;
            List<FinaryAccountMapping> suggestedMappings = new ArrayList<>();

            for (FinaryAccountDto acc : allAccounts) {
                String category = findCategoryForAccount(acc, allAccounts, externalIdToCategory);
                String externalId = "finary_" + category + "_" + acc.id();

                Optional<Account> existingAccount = accountRepository.findByExternalAccountIdAndMemberId(externalId, memberId);
                if (existingAccount.isPresent()) {
                    suggestedMappings.add(new FinaryAccountMapping(
                        acc.name(), category, FinaryMappingAction.MAP_EXISTING,
                        existingAccount.get().getId(), null
                    ));
                } else {
                    allAutoMapped = false;
                    suggestedMappings.clear();
                    break;
                }
            }

            // Update session status
            session.setLastSyncedAt(Instant.now());
            session.setStatus("CONNECTED");
            finarySessionRepository.save(session);

            log.info("Preview ready: {} accounts, {} total transactions, autoMapped={}", allAccounts.size(), totalTx, allAutoMapped);
            return new FinaryPreviewResponse(previews, existing, totalTx, syncToken, allAutoMapped, suggestedMappings);

        } catch (SyncException | TotpRequiredException e) {
            throw e;
        } catch (Exception e) {
            log.error("Finary API preview failed: {}", e.getMessage(), e);
            throw new SyncException("Finary API preview failed: " + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------------------
    // Execute phase
    // ---------------------------------------------------------------------------

    /**
     * Execute phase: retrieve cached data, apply mappings, create/update accounts, import transactions.
     */
    @Transactional
    public FinaryImportResultResponse execute(String syncToken, List<FinaryAccountMapping> mappings, Long memberId) {
        SyncSessionData session = cache.get(syncToken);
        if (session == null) {
            throw new SyncException("Sync session expired or invalid -- please start a new sync");
        }
        cache.remove(syncToken);

        FamilyMember member = familyMemberRepository.findById(memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));

        int accountsCreated = 0;
        int accountsMapped = 0;
        int accountsSkipped = 0;
        int transactionsImported = 0;
        List<ImportedAccountSummary> imported = new ArrayList<>();

        // Map finaryName -> FinaryAccountDto (we need to find by name since that's what mappings use)
        Map<String, FinaryAccountDto> finaryByName = session.allAccounts().stream()
            .collect(Collectors.toMap(FinaryAccountDto::name, a -> a, (a, b) -> a));

        for (FinaryAccountMapping mapping : mappings) {
            FinaryAccountDto finaryAcc = finaryByName.get(mapping.finaryName());
            if (finaryAcc == null) continue;

            String cat = session.externalIdToCategory().get("finary_" + mapping.finaryCategory() + "_" + finaryAcc.id());
            String category = cat != null ? cat : mapping.finaryCategory();
            String externalId = "finary_" + category + "_" + finaryAcc.id();

            Account account = null;

            if (mapping.action() == FinaryMappingAction.SKIP) {
                accountsSkipped++;
                continue;
            } else if (mapping.action() == FinaryMappingAction.MAP_EXISTING) {
                account = accountRepository.findByIdAndMemberId(mapping.targetAccountId(), memberId)
                    .orElseThrow(() -> new SyncException(
                        "Account " + mapping.targetAccountId() + " not found"));
                account.setCurrentBalance(BigDecimal.valueOf(finaryAcc.balance() != null ? finaryAcc.balance() : 0));
                account.setCurrency(finaryAcc.currency() != null ? finaryAcc.currency().code() : "EUR");
                account.setLastSyncedAt(Instant.now());
                account.setExternalAccountId(externalId);
                accountRepository.save(account);
                accountsMapped++;
                log.debug("Mapped account: {} -> {} (balance: {})", finaryAcc.name(), account.getName(), finaryAcc.balance());
            } else if (mapping.action() == FinaryMappingAction.CREATE_NEW) {
                // Check if an account with this external ID already exists (upsert pattern)
                Account existingAccount = accountRepository.findByExternalAccountIdAndMemberId(externalId, memberId).orElse(null);
                NewAccountDetails det = mapping.newAccount();

                if (existingAccount != null) {
                    // Update existing account instead of creating duplicate
                    existingAccount.setCurrentBalance(BigDecimal.valueOf(finaryAcc.balance() != null ? finaryAcc.balance() : 0));
                    existingAccount.setCurrency(finaryAcc.currency() != null ? finaryAcc.currency().code() : "EUR");
                    existingAccount.setLastSyncedAt(Instant.now());
                    account = accountRepository.save(existingAccount);
                    accountsMapped++;
                    log.debug("Upserted existing account (CREATE_NEW with existing externalId): {} (balance: {})", account.getName(), finaryAcc.balance());
                } else {
                    // Create new account
                    account = Account.builder()
                        .member(member)
                        .name(det.name())
                        .type(det.type())
                        .provider(det.provider() != null ? det.provider() : "Finary")
                        .currency(det.currency())
                        .currentBalance(BigDecimal.valueOf(finaryAcc.balance() != null ? finaryAcc.balance() : 0))
                        .isManual(true)
                        .color(det.color() != null ? det.color() : FinaryPersistenceHelper.defaultColorForType(det.type()))
                        .externalAccountId(externalId)
                        .lastSyncedAt(Instant.now())
                        .build();
                    account = accountRepository.save(account);
                    accountsCreated++;
                    log.debug("Created account: {} (balance: {})", account.getName(), finaryAcc.balance());
                }
            }

            if (account != null) {
                // Import transactions for this account
                List<FinaryTransactionDto> categoryTxs = session.transactionsByCategory().getOrDefault(category, List.of());
                List<FinaryTransactionDto> accountTxs = categoryTxs.stream()
                    .filter(tx -> tx.account().id().equals(finaryAcc.id()))
                    .toList();

                if (!accountTxs.isEmpty()) {
                    final Account finalAccount = account;
                    FinaryPersistenceHelper.ParsedFinaryAccount fakeAcc = new FinaryPersistenceHelper.ParsedFinaryAccount(
                        finalAccount.getName(), "Finary", category,
                        finalAccount.getCurrentBalance(), finalAccount.getCurrency()
                    );

                    List<FinaryPersistenceHelper.ParsedFinaryTransaction> parsedTx = accountTxs.stream()
                        .map(tx -> new FinaryPersistenceHelper.ParsedFinaryTransaction(
                            finalAccount.getName(),
                            Instant.parse(tx.date()).atZone(ZoneOffset.UTC).toLocalDate(),
                            tx.displayName() != null ? tx.displayName() : tx.name(),
                            BigDecimal.valueOf(tx.value() != null ? tx.value() : 0),
                            tx.transactionType(),
                            tx.category() != null ? tx.category().name() : "",
                            tx.currency().code()
                        ))
                        .collect(Collectors.toList());

                    transactionsImported += persistenceHelper.importTransactions(finalAccount, fakeAcc, parsedTx);
                    persistenceHelper.reconstructSnapshotsFromDb(finalAccount);
                }

                imported.add(new ImportedAccountSummary(
                    account.getId(), account.getName(), account.getType(),
                    account.getCurrentBalance().doubleValue(), account.getColor()
                ));
            }
        }

        log.info("Finary sync completed: {} created, {} mapped, {} skipped, {} transactions",
            accountsCreated, accountsMapped, accountsSkipped, transactionsImported);

        return new FinaryImportResultResponse(
            accountsCreated, accountsMapped, accountsSkipped,
            0, transactionsImported, imported
        );
    }

    // ---------------------------------------------------------------------------
    // Auto-sync
    // ---------------------------------------------------------------------------

    /**
     * Auto-sync: preview + execute in one step if all accounts are already mapped.
     * Returns NEEDS_MAPPING if new accounts are discovered (user must go through mapping UI).
     */
    public FinaryAutoSyncResponse autoSync(Long memberId) {
        Optional<FinarySession> sessionOpt = finarySessionRepository.findByMemberId(memberId);
        if (sessionOpt.isEmpty()) {
            return new FinaryAutoSyncResponse("NOT_CONNECTED", 0, 0);
        }
        FinarySession existingSession = sessionOpt.get();
        if ("TOTP_REQUIRED".equals(existingSession.getStatus())) {
            return new FinaryAutoSyncResponse("TOTP_REQUIRED", 0, 0);
        }
        if (!"CONNECTED".equals(existingSession.getStatus())) {
            return new FinaryAutoSyncResponse("NOT_CONNECTED", 0, 0);
        }
        try {
            FinaryPreviewResponse preview = preview(null, memberId);
            if (preview.autoMapped()) {
                FinaryImportResultResponse result = execute(preview.fileToken(), preview.suggestedMappings(), memberId);
                return new FinaryAutoSyncResponse("OK", result.accountsCreated() + result.accountsMapped(), 0);
            } else {
                return new FinaryAutoSyncResponse("NEEDS_MAPPING", 0, preview.accounts().size());
            }
        } catch (TotpRequiredException e) {
            existingSession.setStatus("TOTP_REQUIRED");
            finarySessionRepository.save(existingSession);
            return new FinaryAutoSyncResponse("TOTP_REQUIRED", 0, 0);
        } catch (SyncException e) {
            log.error("Finary auto-sync failed for member {}: {}", memberId, e.getMessage(), e);
            throw e;
        } catch (RuntimeException e) {
            log.error("Finary auto-sync failed for member {}: {}", memberId, e.getMessage(), e);
            throw new SyncException("Finary auto-sync failed: " + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /**
     * Fetch all transaction pages for a category (paginate until result < pageSize)
     */
    private List<FinaryTransactionDto> fetchAllTransactionPages(String jwt, FinaryApiClient.OrgContext ctx, String category) {
        List<FinaryTransactionDto> allTx = new ArrayList<>();
        int page = 1;
        int pageSize = 200;

        while (true) {
            List<FinaryTransactionDto> batch = finaryApiClient.fetchCategoryTransactions(jwt, ctx, category, page, pageSize);
            if (batch.isEmpty()) break;
            allTx.addAll(batch);
            if (batch.size() < pageSize) break;
            page++;
        }

        return allTx;
    }

    /**
     * Find the category for a given account by looking up its external ID in the map
     */
    private String findCategoryForAccount(FinaryAccountDto acc, List<FinaryAccountDto> allAccounts,
                                           Map<String, String> externalIdToCategory) {
        for (Map.Entry<String, String> entry : externalIdToCategory.entrySet()) {
            if (entry.getKey().endsWith("_" + acc.id())) {
                return entry.getValue();
            }
        }
        return "other_assets";
    }

    /**
     * Cleanup old cache entries (older than 10 minutes)
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    void cleanupExpiredCache() {
        Instant tenMinutesAgo = Instant.now().minusSeconds(600);
        cache.entrySet().removeIf(e -> e.getValue().createdAt().isBefore(tenMinutesAgo));
    }
}
