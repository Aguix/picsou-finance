package com.picsou.service;

import com.picsou.config.CryptoEncryption;
import com.picsou.dto.AccountResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.exception.SyncException;
import com.picsou.model.*;
import com.picsou.port.CryptoExchangePort;
import com.picsou.port.CryptoExchangePort.CryptoHolding;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.CryptoExchangeSessionRepository;
import com.picsou.repository.FamilyMemberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional
public class CryptoExchangeSyncService {

    private static final Logger log = LoggerFactory.getLogger(CryptoExchangeSyncService.class);

    private final List<CryptoExchangePort> exchangeAdapters;
    private final CryptoExchangeSessionRepository sessionRepository;
    private final AccountRepository accountRepository;
    private final FamilyMemberRepository familyMemberRepository;
    private final AccountService accountService;
    private final PriceService priceService;
    private final CryptoEncryption encryption;

    public CryptoExchangeSyncService(
        List<CryptoExchangePort> exchangeAdapters,
        CryptoExchangeSessionRepository sessionRepository,
        AccountRepository accountRepository,
        FamilyMemberRepository familyMemberRepository,
        AccountService accountService,
        PriceService priceService,
        CryptoEncryption encryption
    ) {
        this.exchangeAdapters = exchangeAdapters;
        this.sessionRepository = sessionRepository;
        this.accountRepository = accountRepository;
        this.familyMemberRepository = familyMemberRepository;
        this.accountService = accountService;
        this.priceService = priceService;
        this.encryption = encryption;
    }

    public AccountResponse addExchange(ExchangeType type, String apiKey, String apiSecret, Long memberId) {
        CryptoExchangePort adapter = findAdapter(type);

        if (!adapter.testConnection(apiKey, apiSecret)) {
            throw new SyncException("Could not connect to " + type + ". Please check your API keys.");
        }

        FamilyMember member = familyMemberRepository.findById(memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));

        Optional<CryptoExchangeSession> existing = sessionRepository.findByExchangeTypeAndMemberId(type, memberId);
        CryptoExchangeSession session;
        if (existing.isPresent()) {
            session = existing.get();
            session.setApiKey(encryption.encrypt(apiKey));
            session.setApiSecret(encryption.encrypt(apiSecret));
            session.setStatus("CONNECTED");
        } else {
            session = CryptoExchangeSession.builder()
                .member(member)
                .exchangeType(type)
                .apiKey(encryption.encrypt(apiKey))
                .apiSecret(encryption.encrypt(apiSecret))
                .status("CONNECTED")
                .build();
        }
        sessionRepository.save(session);

        return sync(session.getId(), memberId);
    }

    public AccountResponse sync(Long sessionId, Long memberId) {
        CryptoExchangeSession session = sessionRepository.findByIdAndMemberId(sessionId, memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Exchange session not found"));

        CryptoExchangePort adapter = findAdapter(session.getExchangeType());
        String decryptedKey = encryption.decrypt(session.getApiKey());
        String decryptedSecret = encryption.decrypt(session.getApiSecret());

        try {
            List<CryptoHolding> holdings = adapter.fetchHoldings(decryptedKey, decryptedSecret);

            Set<String> tickers = holdings.stream()
                .map(CryptoHolding::symbol)
                .collect(Collectors.toSet());
            Map<String, BigDecimal> prices = priceService.refreshPrices(tickers);

            BigDecimal totalEur = BigDecimal.ZERO;
            for (CryptoHolding holding : holdings) {
                BigDecimal price = prices.get(holding.symbol().toUpperCase());
                if (price != null) {
                    totalEur = totalEur.add(
                        holding.quantity().multiply(price).setScale(2, RoundingMode.HALF_UP));
                } else {
                    log.warn("No EUR price for {} -- skipping in total", holding.symbol());
                }
            }

            session.setStatus("CONNECTED");
            session.setLastSyncedAt(Instant.now());
            sessionRepository.save(session);

            String externalId = "crypto_exchange_" + session.getExchangeType().name().toLowerCase();

            // Resolve or create the account (without snapshot yet). If the account
            // was soft-deleted by the user, skip the rest of this sync — the user
            // explicitly removed it and we won't resurrect.
            Account account = resolveAccount(externalId, session.getExchangeType().name(), totalEur, memberId)
                .orElse(null);
            if (account == null) {
                throw new SyncException(
                    "This account's history was deleted. Remove the " + session.getExchangeType().name() +
                    " session in Settings → Crypto to stop syncing it."
                );
            }

            // Persist individual holdings before snapshot (so calculateInvestedAmount finds them)
            for (CryptoHolding holding : holdings) {
                BigDecimal price = prices.get(holding.symbol().toUpperCase());
                if (price != null) {
                    accountService.upsertHolding(account.getId(), memberId, holding.symbol().toUpperCase(),
                        holding.symbol().toUpperCase(), holding.quantity(), price);
                }
            }

            // Now create snapshot with correct invested amount from holdings
            accountService.upsertSnapshot(account, totalEur, LocalDate.now());

            return accountService.toResponse(account);

        } catch (Exception ex) {
            session.setStatus("ERROR");
            sessionRepository.save(session);
            log.warn("Crypto exchange sync failed for {}: {}", session.getExchangeType(), ex.getMessage());
            throw new SyncException("Could not sync your " + session.getExchangeType() + " account. Please try again later.");
        }
    }

    public void removeExchange(Long sessionId, Long memberId) {
        CryptoExchangeSession session = sessionRepository.findByIdAndMemberId(sessionId, memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Exchange session not found"));

        String externalId = "crypto_exchange_" + session.getExchangeType().name().toLowerCase();
        accountRepository.findByExternalAccountIdAndMemberId(externalId, memberId)
            .ifPresent(accountRepository::delete);
        sessionRepository.delete(session);
        log.info("Removed exchange session {} and associated account", sessionId);
    }

    public void resyncAll(Long memberId) {
        List<CryptoExchangeSession> sessions = sessionRepository.findAllByMemberId(memberId);
        for (CryptoExchangeSession session : sessions) {
            try {
                sync(session.getId(), memberId);
            } catch (Exception ex) {
                log.warn("Crypto exchange resync failed for {}: {}", session.getExchangeType(), ex.getMessage());
            }
        }
    }

    @Transactional(readOnly = true)
    public List<ExchangeStatusResponse> getStatus(Long memberId) {
        return sessionRepository.findAllByMemberId(memberId).stream()
            .map(s -> new ExchangeStatusResponse(
                s.getId(), s.getExchangeType(), s.getStatus(), s.getLastSyncedAt()))
            .toList();
    }

    private CryptoExchangePort findAdapter(ExchangeType type) {
        return exchangeAdapters.stream()
            .filter(a -> a.exchangeName().equalsIgnoreCase(type.name()))
            .findFirst()
            .orElseThrow(() -> new SyncException("This exchange isn't supported yet."));
    }

    private Optional<Account> resolveAccount(String externalId, String exchangeName, BigDecimal balanceEur, Long memberId) {
        Optional<Account> existing = accountRepository.findByExternalAccountIdAndMemberId(externalId, memberId);

        if (existing.isEmpty() &&
            accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId(externalId, memberId)) {
            log.info("Crypto exchange: account externalId={} was soft-deleted, skipping sync", externalId);
            return Optional.empty();
        }

        Account account;
        if (existing.isPresent()) {
            account = existing.get();
            account.setCurrentBalance(balanceEur);
            account.setLastSyncedAt(Instant.now());
        } else {
            FamilyMember member = familyMemberRepository.findById(memberId)
                .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));
            account = Account.builder()
                .member(member)
                .name(exchangeName + " Crypto")
                .type(AccountType.CRYPTO)
                .provider(exchangeName)
                .currency("EUR")
                .currentBalance(balanceEur)
                .lastSyncedAt(Instant.now())
                .externalAccountId(externalId)
                .isManual(false)
                .color("#f59e0b")
                .build();
        }

        return Optional.of(accountRepository.save(account));
    }

    public record ExchangeStatusResponse(
        Long id, ExchangeType exchangeType, String status, java.time.Instant lastSyncedAt) {}
}
