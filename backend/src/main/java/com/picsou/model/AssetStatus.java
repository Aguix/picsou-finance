package com.picsou.model;

/**
 * Resolution lifecycle of a {@link FinancialAsset} — how (and whether) its symbol got linked
 * to aggregator ids. See {@code FinancialAssetService} for the transitions.
 */
public enum AssetStatus {
    /** Discovered (import/sync) but not linked to any aggregator yet; retried on next resolve. */
    PENDING,
    /** Linked automatically — single or market-cap-dominant search match. */
    AUTO,
    /** Linked or corrected by the operator. */
    USER,
    /** Delisted coin no aggregator can price — pinned to a known zero instead of left unpriced. */
    WORTHLESS
}
