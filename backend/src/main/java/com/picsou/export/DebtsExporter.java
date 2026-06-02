package com.picsou.export;

import com.fasterxml.jackson.core.JsonGenerator;
import com.picsou.model.AppUser;
import com.picsou.model.Debt;
import com.picsou.repository.DebtRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

import static com.picsou.export.AccountsExporter.writeBigDecimal;
import static com.picsou.export.ProfileExporter.nullSafe;
import static com.picsou.export.ProfileExporter.writeInstant;

@Component
class DebtsExporter implements EntityExporter {

    private final DebtRepository debtRepository;

    DebtsExporter(DebtRepository debtRepository) {
        this.debtRepository = debtRepository;
    }

    @Override
    public String name() {
        return "debts";
    }

    @Override
    public List<String> csvHeader() {
        return List.of(
            "id", "account_id", "linked_account_id", "borrowed_amount",
            "interest_rate", "monthly_payment", "lender_name",
            "start_date", "end_date", "insurance_monthly", "file_fees",
            "created_at", "updated_at"
        );
    }

    @Override
    public void writeCsv(AppUser user, ExportContext ctx, CsvWriter csv) throws IOException {
        for (Debt d : debtRepository.findAllByMemberId(user.getMember().getId())) {
            csv.writeRow(List.of(
                String.valueOf(d.getId()),
                String.valueOf(d.getAccount().getId()),
                d.getLinkedAccount() == null ? "" : String.valueOf(d.getLinkedAccount().getId()),
                d.getBorrowedAmount() == null ? "" : d.getBorrowedAmount().toPlainString(),
                d.getInterestRate() == null ? "" : d.getInterestRate().toPlainString(),
                d.getMonthlyPayment() == null ? "" : d.getMonthlyPayment().toPlainString(),
                nullSafe(d.getLenderName()),
                d.getStartDate() == null ? "" : d.getStartDate().toString(),
                d.getEndDate() == null ? "" : d.getEndDate().toString(),
                d.getInsuranceMonthly() == null ? "" : d.getInsuranceMonthly().toPlainString(),
                d.getFileFees() == null ? "" : d.getFileFees().toPlainString(),
                d.getCreatedAt() == null ? "" : d.getCreatedAt().toString(),
                d.getUpdatedAt() == null ? "" : d.getUpdatedAt().toString()
            ));
        }
    }

    @Override
    public void writeJson(AppUser user, ExportContext ctx, JsonGenerator json) throws IOException {
        json.writeStartArray();
        for (Debt d : debtRepository.findAllByMemberId(user.getMember().getId())) {
            json.writeStartObject();
            json.writeNumberField("id", d.getId());
            json.writeNumberField("account_id", d.getAccount().getId());
            if (d.getLinkedAccount() != null) {
                json.writeNumberField("linked_account_id", d.getLinkedAccount().getId());
            } else {
                json.writeNullField("linked_account_id");
            }
            writeBigDecimal(json, "borrowed_amount", d.getBorrowedAmount());
            writeBigDecimal(json, "interest_rate", d.getInterestRate());
            writeBigDecimal(json, "monthly_payment", d.getMonthlyPayment());
            json.writeStringField("lender_name", d.getLenderName());
            json.writeStringField("start_date", d.getStartDate() == null ? null : d.getStartDate().toString());
            json.writeStringField("end_date", d.getEndDate() == null ? null : d.getEndDate().toString());
            writeBigDecimal(json, "insurance_monthly", d.getInsuranceMonthly());
            writeBigDecimal(json, "file_fees", d.getFileFees());
            writeInstant(json, "created_at", d.getCreatedAt());
            writeInstant(json, "updated_at", d.getUpdatedAt());
            json.writeEndObject();
        }
        json.writeEndArray();
    }
}
