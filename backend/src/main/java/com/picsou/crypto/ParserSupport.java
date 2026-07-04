package com.picsou.crypto;

import com.picsou.model.RewardKind;
import com.picsou.model.TransactionType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;

/**
 * Shared parsing helpers for {@link CryptoCsvParser} implementations: tolerant number/date
 * parsing, the fiat-ticker set, and factory methods that encode Picsou's sign conventions
 * (BUY = negative cash flow, SELL/REWARD = positive; REWARD price-per-unit is always ZERO so
 * free coins dilute the VWAP average buy-in).
 */
public final class ParserSupport {

    private ParserSupport() {}

    /** ISO-style fiat tickers we never treat as a crypto position. */
    public static final Set<String> FIAT = Set.of(
        "EUR", "USD", "GBP", "CHF", "CAD", "AUD", "JPY", "SGD", "BRL", "PLN", "TRY", "RON",
        "NOK", "SEK", "DKK", "CZK", "HUF", "NZD", "HKD");

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static boolean isFiat(String ticker) {
        return ticker != null && FIAT.contains(ticker.trim().toUpperCase());
    }

    /** Tolerant decimal: strips thousands separators and currency symbols; ZERO on failure. */
    public static BigDecimal num(String s) {
        if (s == null || s.isBlank()) {
            return BigDecimal.ZERO;
        }
        String cleaned = s.trim()
            .replace(" ", "")
            .replace(" ", "")
            .replace("€", "")
            .replace("$", "");
        // "1.234,56" (French) vs "1,234.56" (English): when both separators appear, the last one
        // is the decimal mark; a lone comma is a decimal mark only if not a thousands pattern.
        int lastComma = cleaned.lastIndexOf(',');
        int lastDot = cleaned.lastIndexOf('.');
        if (lastComma >= 0 && lastDot >= 0) {
            cleaned = lastComma > lastDot
                ? cleaned.replace(".", "").replace(',', '.')
                : cleaned.replace(",", "");
        } else if (lastComma >= 0) {
            cleaned = cleaned.replace(',', '.');
        }
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * Tolerant date: accepts {@code yyyy-MM-dd HH:mm:ss}, an ISO timestamp, or anything starting
     * with an ISO date ({@code yyyy-MM-dd…}); also {@code dd/MM/yyyy}. Null when unparseable.
     */
    public static LocalDate parseDate(String ts) {
        if (ts == null || ts.isBlank()) {
            return null;
        }
        String s = ts.trim();
        try {
            return LocalDateTime.parse(s, DATE_TIME).toLocalDate();
        } catch (Exception ignored) {
            // fall through
        }
        try {
            return LocalDate.parse(s.substring(0, Math.min(10, s.length())));
        } catch (Exception ignored) {
            // fall through
        }
        try {
            return LocalDate.parse(s.substring(0, Math.min(10, s.length())),
                DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        } catch (Exception e) {
            return null;
        }
    }

    public static String upper(String s) {
        return s == null ? "" : s.trim().toUpperCase();
    }

    /** Lower-cased, accent-stripped, trimmed — for matching localized operation labels. */
    public static String fold(String s) {
        if (s == null) {
            return "";
        }
        String lowered = s.trim().toLowerCase();
        return Normalizer.normalize(lowered, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    public static ParsedCryptoTx buy(LocalDate date, String desc, String ticker, BigDecimal qty,
                                     BigDecimal nativeValue, String nativeCcy, String kind) {
        BigDecimal pricePerUnit = qty.signum() > 0 && nativeValue.signum() > 0
            ? nativeValue.divide(qty, 8, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        return new ParsedCryptoTx(date, desc, ticker, ticker, TransactionType.BUY, null,
            qty, pricePerUnit, nativeValue.abs().negate(), nativeCcy, kind);
    }

    public static ParsedCryptoTx sell(LocalDate date, String desc, String ticker, BigDecimal qty,
                                      BigDecimal nativeValue, String nativeCcy, String kind) {
        BigDecimal pricePerUnit = qty.signum() > 0 && nativeValue.signum() > 0
            ? nativeValue.divide(qty, 8, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        return new ParsedCryptoTx(date, desc, ticker, ticker, TransactionType.SELL, null,
            qty, pricePerUnit, nativeValue.abs(), nativeCcy, kind);
    }

    /** Reward = zero-cost acquisition: price-per-unit stays ZERO so the VWAP average dilutes. */
    public static ParsedCryptoTx reward(LocalDate date, String desc, String ticker, BigDecimal qty,
                                        RewardKind rewardKind, BigDecimal nativeValue,
                                        String nativeCcy, String kind) {
        return new ParsedCryptoTx(date, desc, ticker, ticker, TransactionType.REWARD, rewardKind,
            qty, BigDecimal.ZERO, nativeValue.abs(), nativeCcy, kind);
    }

    /** A row kept for visibility only — never influences holdings ({@code txType == null}). */
    public static ParsedCryptoTx inert(LocalDate date, String desc, String ticker,
                                       BigDecimal amount, String nativeCcy, String kind) {
        return new ParsedCryptoTx(date, desc,
            ticker == null || ticker.isBlank() ? null : ticker, null, null, null,
            amount == null || amount.abs().signum() == 0 ? null : amount.abs(), null,
            amount, nativeCcy, kind);
    }
}
