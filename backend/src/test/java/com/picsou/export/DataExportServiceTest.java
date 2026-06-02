package com.picsou.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.model.*;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataExportServiceTest {

    @Mock private AccountRepository accountRepository;
    @Mock private AccountHoldingRepository holdingRepository;

    private DataExportService service;
    private AppUser user;
    private FamilyMember member;

    private static final String SECRET_PASSWORD_HASH = "$2a$10$NeVeRsHoUlDaPpEaR";
    private static final String SECRET_TOTP = "JBSWY3DPEHPK3PXPSeCrEt";
    private static final String SECRET_TOKEN = "ACTIVATION_TOKEN_aBcDeF123";

    @BeforeEach
    void setUp() {
        member = FamilyMember.builder()
            .id(42L).displayName("Chloé Test").avatarColor("#abcdef").managed(false)
            .build();
        user = AppUser.builder()
            .id(7L).username("chloe").role(UserRole.MEMBER)
            .passwordHash(SECRET_PASSWORD_HASH)
            .activationToken(SECRET_TOKEN)
            .activated(true).acknowledgedWarning(true)
            .member(member)
            .build();

        ProfileExporter profile = new ProfileExporter();
        AccountsExporter accounts = new AccountsExporter(accountRepository);
        HoldingsExporter holdings = new HoldingsExporter(accountRepository, holdingRepository);
        service = new DataExportService(List.of(profile, accounts, holdings));
    }

    @Test
    void exportsZipWithExpectedEntries_andNoSecretLeak() throws Exception {
        Account a = Account.builder()
            .id(100L).member(member).name("Crypto wallet").type(AccountType.CRYPTO)
            .currency("EUR").currentBalance(new BigDecimal("12345.67"))
            .ticker("BTC").color("#fa0").isManual(true).provider("manual")
            .lastSyncedAt(Instant.parse("2026-01-01T00:00:00Z"))
            .build();
        AccountHolding h = AccountHolding.builder()
            .id(200L).account(a).ticker("BTC").name("Bitcoin")
            .quantity(new BigDecimal("0.5")).averageBuyIn(new BigDecimal("30000"))
            .currentPrice(new BigDecimal("65000"))
            .build();

        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(42L))
            .thenReturn(List.of(a));
        when(holdingRepository.findByAccount_Id(100L)).thenReturn(List.of(h));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.export(user, ExportContext.defaults(), out);
        byte[] zipBytes = out.toByteArray();

        Map<String, byte[]> entries = readZip(zipBytes);
        assertThat(entries.keySet())
            .contains("data.json", "profile.csv", "accounts.csv", "holdings.csv", "README.txt");

        String json = new String(entries.get("data.json"));
        assertThat(json).contains("\"username\" : \"chloe\"");
        assertThat(json).contains("\"ticker\" : \"BTC\"");

        JsonNode parsed = new ObjectMapper().readTree(json);
        assertThat(parsed.get("schema_version").asText()).isEqualTo("1");
        assertThat(parsed.get("user_id").asLong()).isEqualTo(7L);
        assertThat(parsed.get("profile").get("username").asText()).isEqualTo("chloe");
        assertThat(parsed.get("accounts")).hasSize(1);
        assertThat(parsed.get("holdings")).hasSize(1);

        String accountsCsv = new String(entries.get("accounts.csv"));
        assertThat(accountsCsv).contains("Crypto wallet").contains("BTC").contains("12345.67");

        // Whole-archive byte-grep: NONE of the known secret tokens may appear anywhere
        // (data.json, csv files, README, ZIP metadata) — this is the GDPR safety net.
        String archive = new String(zipBytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertThat(archive).doesNotContain(SECRET_PASSWORD_HASH);
        assertThat(archive).doesNotContain(SECRET_TOTP);
        assertThat(archive).doesNotContain(SECRET_TOKEN);
        assertThat(archive).doesNotContain("password_hash");
        assertThat(archive).doesNotContain("activation_token");
    }

    @Test
    void exportsCleanlyEvenWithNoData() throws Exception {
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(42L)).thenReturn(List.of());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.export(user, ExportContext.defaults(), out);

        Map<String, byte[]> entries = readZip(out.toByteArray());
        assertThat(entries).containsKey("data.json").containsKey("accounts.csv").containsKey("holdings.csv");
        JsonNode parsed = new ObjectMapper().readTree(entries.get("data.json"));
        assertThat(parsed.get("accounts")).hasSize(0);
        assertThat(parsed.get("holdings")).hasSize(0);
    }

    private static Map<String, byte[]> readZip(byte[] bytes) throws Exception {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                zin.transferTo(buf);
                entries.put(e.getName(), buf.toByteArray());
            }
        }
        return entries;
    }
}
