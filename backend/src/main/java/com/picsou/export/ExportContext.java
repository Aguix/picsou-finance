package com.picsou.export;

/**
 * Per-request flags forwarded to every {@link EntityExporter}.
 *
 * Currently only carries the historical-snapshot opt-in: snapshots can grow
 * to many thousands of rows per account, so users who just want their
 * "current state" don't pay for them by default.
 */
public record ExportContext(boolean includeBalanceSnapshots) {
    public static ExportContext defaults() {
        return new ExportContext(false);
    }
}
