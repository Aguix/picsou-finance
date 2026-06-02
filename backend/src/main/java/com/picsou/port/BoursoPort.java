package com.picsou.port;

import com.picsou.model.AccountType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface BoursoPort {

    /**
     * Step 1: Authenticates with BoursoBank using the virtual keyboard challenge.
     *
     * @param customerId BoursoBank client number
     * @param password   numeric password
     * @return result indicating whether MFA is required; if not, sessionCookies is populated
     */
    InitiateResult initiateAuth(String customerId, String password);

    /**
     * Step 2 (MFA only): Completes the MFA flow with the received OTP code.
     *
     * @param processId returned by {@link #initiateAuth}
     * @param code      OTP code (from SMS, email, or app notification)
     * @return serialized session cookies to store in DB
     */
    String completeAuth(String processId, String code);

    /**
     * Fetches all accounts with current balances, positions (for PEA/CTO),
     * and recent transactions (last 90 days).
     *
     * @param sessionCookies serialized cookies returned by auth flow
     */
    List<BoursoAccountData> fetchAccounts(String sessionCookies);

    record InitiateResult(
        String processId,
        boolean mfaRequired,
        String mfaType,
        String contact,
        String sessionCookies   // populated only when mfaRequired == false
    ) {}

    record BoursoPosition(
        String isin,
        String symbol,
        String label,
        BigDecimal quantity,
        BigDecimal buyingPrice,
        BigDecimal currentPrice
    ) {}

    record BoursoTransaction(
        LocalDate date,
        String label,
        BigDecimal amount,
        String category
    ) {}

    record BoursoAccountData(
        String externalId,
        String name,
        AccountType type,
        BigDecimal balanceEur,
        List<BoursoPosition> positions,
        List<BoursoTransaction> transactions
    ) {}
}
