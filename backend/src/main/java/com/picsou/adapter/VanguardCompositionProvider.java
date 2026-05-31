package com.picsou.adapter;

import com.picsou.port.EtfCompositionProvider;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/**
 * Vanguard ETF holdings provider.
 *
 * TODO: resolve the Vanguard holdings endpoint (vanguard.com / vanguard EU
 * fund APIs). Until then {@link #fetch} returns empty and the UI shows the
 * asset-type badge without breakdowns. Document the endpoint in the feature
 * note once found.
 */
@Component
public class VanguardCompositionProvider implements EtfCompositionProvider {

    @Override
    public boolean supports(String ticker, String name) {
        return name != null && name.toLowerCase(Locale.ROOT).contains("vanguard");
    }

    @Override
    public Optional<RawEtfHoldings> fetch(String ticker, String name) {
        return Optional.empty();
    }
}
