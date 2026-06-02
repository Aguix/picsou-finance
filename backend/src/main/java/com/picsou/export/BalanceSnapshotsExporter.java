package com.picsou.export;

import com.fasterxml.jackson.core.JsonGenerator;
import com.picsou.model.Account;
import com.picsou.model.AppUser;
import com.picsou.model.BalanceSnapshot;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.BalanceSnapshotRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

import static com.picsou.export.AccountsExporter.writeBigDecimal;

/**
 * Daily balance snapshots — opt-in (can dwarf the rest of the archive for
 * users with years of history × tens of accounts). Only emitted when the
 * caller sets {@code includeBalanceSnapshots=true} on the request.
 */
@Component
class BalanceSnapshotsExporter implements EntityExporter {

    private final AccountRepository accountRepository;
    private final BalanceSnapshotRepository balanceSnapshotRepository;

    BalanceSnapshotsExporter(AccountRepository accountRepository,
                             BalanceSnapshotRepository balanceSnapshotRepository) {
        this.accountRepository = accountRepository;
        this.balanceSnapshotRepository = balanceSnapshotRepository;
    }

    @Override
    public boolean enabled(ExportContext ctx) {
        return ctx.includeBalanceSnapshots();
    }

    @Override
    public String name() {
        return "balance_snapshots";
    }

    @Override
    public List<String> csvHeader() {
        return List.of("id", "account_id", "date", "balance", "invested_amount", "created_at");
    }

    @Override
    public void writeCsv(AppUser user, ExportContext ctx, CsvWriter csv) throws IOException {
        for (BalanceSnapshot s : snapshots(user)) {
            csv.writeRow(List.of(
                String.valueOf(s.getId()),
                String.valueOf(s.getAccount().getId()),
                s.getDate() == null ? "" : s.getDate().toString(),
                s.getBalance() == null ? "" : s.getBalance().toPlainString(),
                s.getInvestedAmount() == null ? "" : s.getInvestedAmount().toPlainString(),
                s.getCreatedAt() == null ? "" : s.getCreatedAt().toString()
            ));
        }
    }

    @Override
    public void writeJson(AppUser user, ExportContext ctx, JsonGenerator json) throws IOException {
        json.writeStartArray();
        for (BalanceSnapshot s : snapshots(user)) {
            json.writeStartObject();
            json.writeNumberField("id", s.getId());
            json.writeNumberField("account_id", s.getAccount().getId());
            json.writeStringField("date", s.getDate() == null ? null : s.getDate().toString());
            writeBigDecimal(json, "balance", s.getBalance());
            writeBigDecimal(json, "invested_amount", s.getInvestedAmount());
            json.writeStringField("created_at", s.getCreatedAt() == null ? null : s.getCreatedAt().toString());
            json.writeEndObject();
        }
        json.writeEndArray();
    }

    private List<BalanceSnapshot> snapshots(AppUser user) {
        Long memberId = user.getMember().getId();
        return accountRepository.findAllByMemberIdOrderByCreatedAtAsc(memberId).stream()
            .map(Account::getId)
            .flatMap(id -> balanceSnapshotRepository.findByAccountIdOrderByDateAsc(id).stream())
            .toList();
    }
}
