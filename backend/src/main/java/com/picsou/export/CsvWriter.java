package com.picsou.export;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * RFC 4180 CSV writer.
 *
 * Writes a UTF-8 BOM up front so Excel auto-detects the encoding (without
 * the BOM, accents in IBANs/labels render as mojibake on Windows). Quotes
 * fields that contain commas, quotes, CR or LF, and doubles up internal
 * quote characters per RFC 4180.
 *
 * Not thread-safe — one instance per ZIP entry.
 */
final class CsvWriter implements Closeable {

    private static final String LINE_END = "\r\n";

    private final BufferedWriter writer;
    private boolean bomWritten;

    CsvWriter(OutputStream out) {
        this.writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
    }

    void writeRow(List<String> fields) throws IOException {
        if (!bomWritten) {
            writer.write('﻿');
            bomWritten = true;
        }
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) writer.write(',');
            writer.write(escape(fields.get(i)));
        }
        writer.write(LINE_END);
    }

    void flush() throws IOException {
        writer.flush();
    }

    @Override
    public void close() throws IOException {
        writer.flush();
    }

    private static String escape(String field) {
        if (field == null) return "";
        boolean needsQuoting =
            field.indexOf(',') >= 0
            || field.indexOf('"') >= 0
            || field.indexOf('\n') >= 0
            || field.indexOf('\r') >= 0;
        if (!needsQuoting) return field;
        return "\"" + field.replace("\"", "\"\"") + "\"";
    }
}
