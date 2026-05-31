package com.picsou.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.port.EtfCompositionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fetches ETF holdings from iShares / BlackRock public fund pages — no auth.
 *
 * Two-step flow:
 *  1. Resolve {@code ticker → productPageUrl} via the public product-screener
 *     JSON (cached for a day). The screener lists every UCITS fund with its
 *     local exchange ticker, ISIN and product page URL.
 *  2. Download the holdings CSV from
 *     {@code https://www.ishares.com{productPageUrl}/1467271812596.ajax?fileType=csv&fileName={ticker}_holdings&dataType=fund}
 *     and parse the per-holding {@code Weight (%)}, {@code Sector} and
 *     {@code Location} (country) columns.
 *
 * The screener URL and CSV layout are unofficial and may change; failures are
 * swallowed and surface as "composition unavailable" upstream.
 */
@Component
public class IsharesCompositionProvider implements EtfCompositionProvider {

    private static final Logger log = LoggerFactory.getLogger(IsharesCompositionProvider.class);
    private static final String SOURCE = "iShares";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final Duration CATALOG_TTL = Duration.ofHours(24);
    private static final String HOST = "https://www.ishares.com";

    // Broad English UCITS catalog (UK "one" config). Adjustable if iShares changes paths.
    private static final String SCREENER_URL =
        "/uk/individual/en/product-screener/product-screener-v3.1.jsn"
        + "?dcrPath=/templatedata/config/product-screener-v3/data/en/uk-one/product-screener-backend-config"
        + "&siteEntryPassthrough=true";

    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();
    // Cached catalog: lookup key (ticker or ISIN, uppercased) → productPageUrl.
    private final AtomicReference<CachedCatalog> catalog = new AtomicReference<>();

    public IsharesCompositionProvider() {
        this(WebClient.builder()
            .baseUrl(HOST)
            .defaultHeader("Accept", "application/json,text/csv,*/*")
            .defaultHeader("User-Agent", "Mozilla/5.0")
            .codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
            .build());
    }

    // Package-private for tests — inject a WebClient backed by an ExchangeFunction.
    IsharesCompositionProvider(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public boolean supports(String ticker, String name) {
        return name != null && name.toLowerCase(Locale.ROOT).contains("ishares");
    }

    @Override
    public Optional<RawEtfHoldings> fetch(String ticker, String name) {
        if (ticker == null || ticker.isBlank()) return Optional.empty();
        try {
            Optional<String> productUrl = resolveProductUrl(ticker, name);
            if (productUrl.isEmpty()) {
                log.debug("iShares: no product page resolved for {}", ticker);
                return Optional.empty();
            }
            String csv = downloadHoldingsCsv(productUrl.get(), bareTicker(ticker));
            if (csv == null || csv.isBlank()) return Optional.empty();

            ParsedCsv parsed = parseHoldingsCsv(csv);
            if (parsed.holdings().isEmpty()) return Optional.empty();
            return Optional.of(new RawEtfHoldings(parsed.holdings(), SOURCE, parsed.asOf()));
        } catch (Exception ex) {
            log.warn("iShares composition fetch failed for {}: {}", ticker, ex.getMessage());
            return Optional.empty();
        }
    }

    // --- product resolution ------------------------------------------------

    private Optional<String> resolveProductUrl(String ticker, String name) {
        Map<String, String> cat = getCatalog();
        if (cat.isEmpty()) return Optional.empty();
        String bare = bareTicker(ticker).toUpperCase(Locale.ROOT);
        // Try the bare exchange ticker first, then the full ticker (in case it is an ISIN).
        String url = cat.get(bare);
        if (url == null) url = cat.get(ticker.toUpperCase(Locale.ROOT));
        return Optional.ofNullable(url);
    }

    private Map<String, String> getCatalog() {
        CachedCatalog cached = catalog.get();
        if (cached != null && cached.isFresh()) return cached.byKey();
        Map<String, String> fresh = fetchCatalog();
        if (!fresh.isEmpty()) {
            catalog.set(new CachedCatalog(fresh, Instant.now()));
        }
        return fresh;
    }

    private Map<String, String> fetchCatalog() {
        try {
            String body = webClient.get().uri(SCREENER_URL)
                .retrieve().bodyToMono(String.class).timeout(TIMEOUT).block();
            if (body == null || body.isBlank()) return Map.of();
            return parseScreener(body);
        } catch (Exception ex) {
            log.warn("iShares screener fetch failed: {}", ex.getMessage());
            return Map.of();
        }
    }

    /**
     * Parse the product-screener JSON into a {ticker|ISIN → productPageUrl} map.
     * The screener is an object keyed by product id; each value holds fields
     * that are either plain strings or {@code {"r": raw, "d": display}} objects.
     */
    Map<String, String> parseScreener(String json) {
        Map<String, String> out = new HashMap<>();
        try {
            JsonNode root = mapper.readTree(json);
            for (JsonNode product : root) {
                String url = text(product.get("productPageUrl"));
                if (url == null || url.isBlank()) continue;
                String ticker = text(product.get("localExchangeTicker"));
                String isin = text(product.get("isin"));
                if (ticker != null && !ticker.isBlank()) out.put(ticker.toUpperCase(Locale.ROOT), url);
                if (isin != null && !isin.isBlank()) out.put(isin.toUpperCase(Locale.ROOT), url);
            }
        } catch (Exception ex) {
            log.warn("iShares screener parse failed: {}", ex.getMessage());
        }
        return out;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isTextual()) return node.asText();
        if (node.has("r") && !node.get("r").isNull()) return node.get("r").asText();
        if (node.has("d") && !node.get("d").isNull()) return node.get("d").asText();
        return null;
    }

    private String downloadHoldingsCsv(String productPageUrl, String bareTicker) {
        String uri = productPageUrl + "/1467271812596.ajax?fileType=csv&fileName="
            + bareTicker + "_holdings&dataType=fund";
        return webClient.get().uri(uri)
            .retrieve().bodyToMono(String.class).timeout(TIMEOUT).block();
    }

    // --- CSV parsing (the testable core) -----------------------------------

    record ParsedCsv(List<EtfHolding> holdings, LocalDate asOf) {}

    /**
     * Parse an iShares holdings CSV. The file has a short preamble (including a
     * "Fund Holdings as of" date line) followed by a header row containing the
     * "Weight (%)" column; data rows continue until a blank line.
     */
    ParsedCsv parseHoldingsCsv(String csv) {
        String[] lines = csv.replace("\r", "").split("\n");
        LocalDate asOf = null;
        int headerIdx = -1;

        for (int i = 0; i < lines.length; i++) {
            List<String> cells = splitCsvLine(lines[i]);
            if (asOf == null && !cells.isEmpty() && cells.get(0).toLowerCase(Locale.ROOT).contains("holdings as of")) {
                asOf = parseAsOf(cells.size() > 1 ? cells.get(1) : null);
            }
            if (cells.stream().anyMatch(c -> c.trim().equalsIgnoreCase("Weight (%)"))) {
                headerIdx = i;
                break;
            }
        }
        if (headerIdx < 0) return new ParsedCsv(List.of(), asOf);

        List<String> header = splitCsvLine(lines[headerIdx]);
        int nameCol = indexOf(header, "Name");
        int weightCol = indexOf(header, "Weight (%)");
        int sectorCol = indexOf(header, "Sector");
        int locationCol = indexOf(header, "Location");
        if (nameCol < 0 || weightCol < 0) return new ParsedCsv(List.of(), asOf);

        List<EtfHolding> holdings = new ArrayList<>();
        for (int i = headerIdx + 1; i < lines.length; i++) {
            if (lines[i].isBlank()) break; // data block ends at the first blank line
            List<String> cells = splitCsvLine(lines[i]);
            if (cells.size() <= weightCol) continue;
            BigDecimal weight = parseWeight(cells.get(weightCol));
            if (weight == null) continue;
            String name = cell(cells, nameCol);
            if (name == null || name.isBlank()) continue;
            holdings.add(new EtfHolding(
                name.trim(),
                weight,
                cell(cells, locationCol),
                cell(cells, sectorCol)
            ));
        }
        return new ParsedCsv(holdings, asOf);
    }

    private static int indexOf(List<String> header, String label) {
        for (int i = 0; i < header.size(); i++) {
            if (header.get(i).trim().equalsIgnoreCase(label)) return i;
        }
        return -1;
    }

    private static String cell(List<String> cells, int idx) {
        if (idx < 0 || idx >= cells.size()) return null;
        String v = cells.get(idx).trim();
        return v.isEmpty() || v.equals("-") ? null : v;
    }

    private static BigDecimal parseWeight(String raw) {
        if (raw == null) return null;
        String v = raw.trim().replace("%", "").replace(",", "");
        if (v.isEmpty()) return null;
        try {
            return new BigDecimal(v);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static LocalDate parseAsOf(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.trim();
        for (DateTimeFormatter fmt : new DateTimeFormatter[]{
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH)
        }) {
            try {
                return LocalDate.parse(v, fmt);
            } catch (Exception ignored) {
                // try next format
            }
        }
        return null;
    }

    /** Split a CSV line honouring double-quoted fields that may contain commas. */
    static List<String> splitCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    /** Strip the exchange suffix from a ticker ("IWDA.AS" → "IWDA"). */
    private static String bareTicker(String ticker) {
        int dot = ticker.indexOf('.');
        return dot > 0 ? ticker.substring(0, dot) : ticker;
    }

    private record CachedCatalog(Map<String, String> byKey, Instant cachedAt) {
        boolean isFresh() { return Instant.now().isBefore(cachedAt.plus(CATALOG_TTL)); }
    }
}
