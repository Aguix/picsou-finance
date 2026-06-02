package com.picsou.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Port for bank account synchronization providers.
 * Implement this interface to add a new bank connector (e.g. Plaid, Powens).
 */
public interface BankConnectorPort {

    /** Create an authorization link to connect a bank account. */
    InitiateResult initiateConnection(String institutionId);

    /** Exchange the OAuth code from the callback for a session ID. */
    String exchangeCode(String oauthCode);

    /** Fetch balances for all accounts linked to this session. */
    List<AccountData> fetchBalances(String sessionId);

    /**
     * Fetch booked transactions for one account from {@code from} (inclusive) to today.
     * Implementations that don't support transaction history may return an empty list.
     */
    List<TransactionData> fetchTransactions(String sessionId, String externalAccountId, LocalDate from);

    /** Search institutions by name/country. */
    List<InstitutionData> searchInstitutions(String query, String country);

    record InitiateResult(String requisitionId, String authLink) {}

    record AccountData(
        String externalId,
        String name,
        String iban,
        String currency,
        BigDecimal balance
    ) {}

    record TransactionData(
        String externalId,
        LocalDate date,
        BigDecimal amount,
        String currency,
        String counterparty,
        String description
    ) {}

    record InstitutionData(
        String id,
        String name,
        String bic,
        String logoUrl,
        String country
    ) {}
}
