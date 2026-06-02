package com.picsou.adapter;

import java.text.Normalizer;
import java.util.Map;

/**
 * Maps Boursorama's French sector and country labels to stable keys that the
 * frontend translates via i18n. Unknown labels pass through verbatim so a slice
 * is never blank. Matching is accent- and case-insensitive.
 */
final class BoursoramaLabels {

    private BoursoramaLabels() {}

    // Morningstar sector taxonomy used by Boursorama.
    private static final Map<String, String> SECTORS = Map.ofEntries(
        Map.entry("technologie", "technology"),
        Map.entry("services de communication", "communication_services"),
        Map.entry("biens de consommation cyclique", "consumer_cyclical"),
        Map.entry("biens de consommation defensif", "consumer_defensive"),
        Map.entry("sante", "healthcare"),
        Map.entry("industriels", "industrials"),
        Map.entry("services publics", "utilities"),
        Map.entry("matieres premieres de base", "basic_materials"),
        Map.entry("energie", "energy"),
        Map.entry("services financiers", "financial_services"),
        Map.entry("immobilier", "real_estate")
    );

    private static final Map<String, String> COUNTRIES = Map.ofEntries(
        Map.entry("etats-unis", "US"),
        Map.entry("pays-bas", "NL"),
        Map.entry("canada", "CA"),
        Map.entry("france", "FR"),
        Map.entry("royaume-uni", "GB"),
        Map.entry("allemagne", "DE"),
        Map.entry("suisse", "CH"),
        Map.entry("irlande", "IE"),
        Map.entry("japon", "JP"),
        Map.entry("chine", "CN"),
        Map.entry("luxembourg", "LU"),
        Map.entry("australie", "AU"),
        Map.entry("espagne", "ES"),
        Map.entry("italie", "IT"),
        Map.entry("suede", "SE"),
        Map.entry("danemark", "DK"),
        Map.entry("hong kong", "HK"),
        Map.entry("coree du sud", "KR"),
        Map.entry("taiwan", "TW"),
        Map.entry("inde", "IN"),
        Map.entry("bresil", "BR"),
        Map.entry("belgique", "BE"),
        Map.entry("finlande", "FI"),
        Map.entry("norvege", "NO"),
        Map.entry("singapour", "SG")
    );

    static String sectorKey(String frenchLabel) {
        return lookup(SECTORS, frenchLabel);
    }

    static String countryKey(String frenchLabel) {
        return lookup(COUNTRIES, frenchLabel);
    }

    private static String lookup(Map<String, String> map, String label) {
        if (label == null) return null;
        String trimmed = label.trim();
        String key = map.get(normalize(trimmed));
        return key != null ? key : trimmed;
    }

    /** Lowercase, strip accents, collapse spaces — so "États-Unis" == "etats-unis". */
    private static String normalize(String s) {
        String noAccents = Normalizer.normalize(s, Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "");
        return noAccents.toLowerCase().replaceAll("\\s+", " ").trim();
    }
}
