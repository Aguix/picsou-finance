package com.picsou.model;

/** How a {@link CategorizationRule} matches a transaction. */
public enum RuleMatchType {
    /** Exact (case-insensitive) match on the transaction counterparty. */
    COUNTERPARTY,
    /** Substring (case-insensitive) match on counterparty or description. */
    KEYWORD
}
