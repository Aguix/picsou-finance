package com.picsou.port;

/**
 * One aggregator's candidate match for a symbol, on the {@link AssetResolverPort} surface — the
 * aggregator-neutral shape the resolution engine and the import/standing UI speak, regardless of
 * which aggregator produced it.
 *
 * @param id            the aggregator's own id for this candidate, used verbatim in its API calls
 *                      and stored in that aggregator's ref column (e.g. a CoinGecko coin id
 *                      {@code "bitcoin"}, a CoinMarketCap numeric id {@code "1"}, or a Yahoo symbol
 *                      {@code "IWDA.AS"} — for Yahoo the id <em>is</em> the symbol).
 * @param name          human name from the aggregator; informational.
 * @param symbol        the candidate's ticker as the aggregator reports it.
 * @param marketCapRank market-cap rank when the aggregator provides one (smaller = bigger), else
 *                      {@code null} — drives the dominant-match auto-suggestion; an aggregator that
 *                      doesn't rank (e.g. Yahoo) leaves it null and is never auto-suggested.
 */
public record AssetCandidate(String id, String name, String symbol, Integer marketCapRank) {}
