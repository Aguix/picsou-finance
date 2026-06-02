package com.picsou.export;

import com.fasterxml.jackson.core.JsonGenerator;
import com.picsou.model.AppUser;

import java.io.IOException;
import java.util.List;

/**
 * Per-entity export contract used by {@code DataExportService}.
 *
 * Each implementation owns one logical collection (accounts, holdings, …)
 * and knows how to render itself both as a CSV table and as a JSON array
 * (or object) for the same scope: the user, their owned FamilyMember,
 * and any SharedResource where they are the recipient.
 *
 * Implementations MUST NOT include security-sensitive fields
 * (password hashes, TOTP secrets, recovery codes, OAuth refresh tokens,
 * encrypted credentials, …). The data-export feature spec lists the
 * exclusion set; per-entity tests assert the exclusion holds.
 */
interface EntityExporter {

    /** Logical name — also the ZIP entry stem (e.g. {@code "accounts"} → {@code accounts.csv} / appears under JSON key). */
    String name();

    /** CSV header row for this entity. */
    List<String> csvHeader();

    /** Stream the entity's rows to CSV and JSON for the given user's scope. */
    void writeCsv(AppUser user, ExportContext ctx, CsvWriter csv) throws IOException;

    /** Stream the entity's data into the JSON document under {@link #name()}. The generator is positioned to write a value. */
    void writeJson(AppUser user, ExportContext ctx, JsonGenerator json) throws IOException;

    /**
     * Whether this exporter should run for the given context. Default: always.
     * Implementations gate themselves on context flags (e.g. balance snapshots
     * are opt-in to keep small exports lean).
     */
    default boolean enabled(ExportContext ctx) {
        return true;
    }
}
