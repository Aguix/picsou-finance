package com.picsou.crypto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CsvTableTest {

    private static final String HEADER =
        "Timestamp (UTC),Transaction Description,Currency,Amount,To Currency,To Amount,"
        + "Native Currency,Native Amount,Native Amount (in USD),Transaction Kind,Transaction Hash";

    @Test
    void parsesRowsAndResolvesColumnsByName() {
        String csv = HEADER + "\n"
            + "2024-01-15 10:30:00,EUR -> BTC,BTC,0.01,,,EUR,300.00,330.00,viban_purchase,\n";

        CsvTable table = CsvTable.parse(csv);

        assertThat(table.rowCount()).isEqualTo(1);
        assertThat(table.get(0, "timestamp (utc)")).isEqualTo("2024-01-15 10:30:00");
        assertThat(table.get(0, "Currency")).isEqualTo("BTC");
        assertThat(table.get(0, "amount")).isEqualTo("0.01");
        assertThat(table.get(0, "native amount")).isEqualTo("300.00");
        assertThat(table.get(0, "transaction kind")).isEqualTo("viban_purchase");
    }

    @Test
    void resolvesColumnsByNameNotPosition() {
        // Columns reordered, kind moved to the front — name-based mapping must still work.
        String csv = "Transaction Kind,Currency,Amount,Timestamp (UTC),Native Amount,Native Currency\n"
            + "crypto_earn_interest_paid,CRO,5,2024-02-01 00:00:00,1.25,EUR\n";

        CsvTable table = CsvTable.parse(csv);

        assertThat(table.get(0, "transaction kind")).isEqualTo("crypto_earn_interest_paid");
        assertThat(table.get(0, "currency")).isEqualTo("CRO");
        assertThat(table.get(0, "native amount")).isEqualTo("1.25");
    }

    @Test
    void handlesQuotedFieldsWithCommasAndEscapedQuotes() {
        String csv = HEADER + "\n"
            + "2024-03-01 12:00:00,\"Reward, \"\"Supercharger\"\" pool\",CRO,2.5,,,EUR,1.10,1.20,"
            + "supercharger_reward_to_app_credited,\n";

        CsvTable table = CsvTable.parse(csv);

        assertThat(table.rowCount()).isEqualTo(1);
        assertThat(table.get(0, "transaction description")).isEqualTo("Reward, \"Supercharger\" pool");
        assertThat(table.get(0, "currency")).isEqualTo("CRO");
    }

    @Test
    void skipsBlankLinesAndBom() {
        String csv = "﻿" + HEADER + "\n"
            + "2024-01-01 00:00:00,Buy,BTC,0.1,,,EUR,3000,3300,crypto_purchase,\n"
            + "\n";

        CsvTable table = CsvTable.parse(csv);

        assertThat(table.rowCount()).isEqualTo(1);
        assertThat(table.get(0, "currency")).isEqualTo("BTC");
        // BOM must not corrupt the first header name.
        assertThat(table.hasColumns("timestamp (utc)")).isTrue();
    }

    @Test
    void detectsSemicolonSeparatedExports() {
        String csv = "Date;Type;Montant (BTC);Montant (EUR)\n"
            + "2024-05-01;Achat;0.001;60,50\n";

        CsvTable table = CsvTable.parse(csv);

        assertThat(table.hasColumns("date", "type", "montant (btc)")).isTrue();
        assertThat(table.get(0, "montant (eur)")).isEqualTo("60,50");
    }

    @Test
    void hasColumnsReportsMissingHeaders() {
        CsvTable table = CsvTable.parse("Date,Label,Amount\n2024-01-01,Coffee,-3.50\n");
        assertThat(table.hasColumns("date", "label")).isTrue();
        assertThat(table.hasColumns("transaction kind")).isFalse();
        assertThat(table.hasAnyColumn("nope", "amount")).isTrue();
    }
}
