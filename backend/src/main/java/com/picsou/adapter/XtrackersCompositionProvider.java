package com.picsou.adapter;

import com.picsou.port.EtfCompositionProvider;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/**
 * Xtrackers (DWS) ETF holdings provider.
 *
 * TODO: resolve the Xtrackers holdings endpoint (etf.dws.com). Until then
 * {@link #fetch} returns empty and the UI shows the asset-type badge without
 * breakdowns. Document the endpoint in the feature note once found.
 */
@Component
public class XtrackersCompositionProvider implements EtfCompositionProvider {

    @Override
    public boolean supports(String ticker, String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.contains("xtrackers") || n.contains("x-trackers");
    }

    @Override
    public Optional<RawEtfHoldings> fetch(String ticker, String name) {
        return Optional.empty();
    }
}
