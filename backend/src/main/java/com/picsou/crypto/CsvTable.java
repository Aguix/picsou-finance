package com.picsou.crypto;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A parsed CSV document: a header row plus data rows, with columns resolved by
 * <em>normalized header name</em> (trimmed, lower-cased, whitespace collapsed) rather than by
 * position, so a column reorder or a slightly different export version does not silently shift
 * every field.
 *
 * <p>The reader is a minimal RFC-4180 implementation: quoted fields, embedded separators/newlines
 * and doubled-quote (<code>""</code>) escaping are handled, and a UTF-8 BOM is stripped. The field
 * separator is auto-detected between {@code ','} and {@code ';'} (some European exports are
 * semicolon-separated). Parsing never throws on a malformed value — typing/validation is the
 * job of each {@link CryptoCsvParser} downstream.
 */
public final class CsvTable {

    private final Map<String, Integer> headerIndex;
    private final List<List<String>> rows;

    private CsvTable(Map<String, Integer> headerIndex, List<List<String>> rows) {
        this.headerIndex = headerIndex;
        this.rows = rows;
    }

    /** Parse raw CSV text. Returns an empty table (no columns, no rows) for blank input. */
    public static CsvTable parse(String csv) {
        List<List<String>> records = splitRecords(csv, detectSeparator(csv));
        if (records.isEmpty()) {
            return new CsvTable(Map.of(), List.of());
        }
        Map<String, Integer> index = new HashMap<>();
        List<String> header = records.get(0);
        for (int i = 0; i < header.size(); i++) {
            index.put(normalize(header.get(i)), i);
        }
        List<List<String>> rows = new ArrayList<>();
        for (int i = 1; i < records.size(); i++) {
            List<String> r = records.get(i);
            if (r.isEmpty() || (r.size() == 1 && r.get(0).isBlank())) {
                continue;
            }
            rows.add(r);
        }
        return new CsvTable(index, rows);
    }

    /** True when every given (normalized) column name exists in the header. */
    public boolean hasColumns(String... names) {
        for (String n : names) {
            if (!headerIndex.containsKey(normalize(n))) {
                return false;
            }
        }
        return true;
    }

    /** True when at least one of the given column names exists in the header. */
    public boolean hasAnyColumn(String... names) {
        for (String n : names) {
            if (headerIndex.containsKey(normalize(n))) {
                return true;
            }
        }
        return false;
    }

    public int rowCount() {
        return rows.size();
    }

    /** Trimmed cell value at {@code row} for the given column name, or {@code ""} when absent. */
    public String get(int row, String column) {
        Integer idx = headerIndex.get(normalize(column));
        if (idx == null) {
            return "";
        }
        List<String> r = rows.get(row);
        return idx < r.size() ? r.get(idx).trim() : "";
    }

    /**
     * Cell value trying several column-name aliases in order — used by parsers whose source has
     * shipped multiple header variants (localized or renamed columns).
     */
    public String getAny(int row, String... columns) {
        for (String c : columns) {
            Integer idx = headerIndex.get(normalize(c));
            if (idx != null) {
                List<String> r = rows.get(row);
                return idx < r.size() ? r.get(idx).trim() : "";
            }
        }
        return "";
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    /** Pick {@code ';'} only when the first line has semicolons and no commas (French exports). */
    private static char detectSeparator(String csv) {
        if (csv == null) {
            return ',';
        }
        int eol = csv.indexOf('\n');
        String firstLine = eol >= 0 ? csv.substring(0, eol) : csv;
        return (firstLine.indexOf(';') >= 0 && firstLine.indexOf(',') < 0) ? ';' : ',';
    }

    /** Split the whole document into records of fields, honouring quotes spanning separators/newlines. */
    private static List<List<String>> splitRecords(String csv, char sep) {
        List<List<String>> records = new ArrayList<>();
        if (csv == null || csv.isEmpty()) {
            return records;
        }
        if (csv.charAt(0) == '﻿') {
            csv = csv.substring(1);
        }

        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int n = csv.length();

        for (int i = 0; i < n; i++) {
            char c = csv.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && csv.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == sep) {
                current.add(field.toString());
                field.setLength(0);
            } else if (c == '\r') {
                // ignore — handled by \n
            } else if (c == '\n') {
                current.add(field.toString());
                field.setLength(0);
                records.add(current);
                current = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (field.length() > 0 || !current.isEmpty()) {
            current.add(field.toString());
            records.add(current);
        }
        return records;
    }
}
