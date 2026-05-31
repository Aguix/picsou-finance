package com.picsou.adapter;

import com.picsou.port.EtfCompositionProvider;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/**
 * Amundi / Lyxor ETF holdings provider.
 *
 * TODO: resolve the Amundi holdings endpoint. Amundi publishes composition on
 * its product pages (amundietf.fr / amundietf.com) but the download URL/format
 * is not yet wired up. Until then {@link #fetch} returns empty and the UI shows
 * the asset-type badge without breakdowns. Document the endpoint in the feature
 * note once found.
 */
@Component
public class AmundiCompositionProvider implements EtfCompositionProvider {

    @Override
    public boolean supports(String ticker, String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.contains("amundi") || n.contains("lyxor");
    }

    @Override
    public Optional<RawEtfHoldings> fetch(String ticker, String name) {
        return Optional.empty();
    }
}
