package com.picsou.export;

import com.fasterxml.jackson.core.JsonGenerator;
import com.picsou.model.AppUser;
import com.picsou.model.Requisition;
import com.picsou.repository.RequisitionRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

import static com.picsou.export.ProfileExporter.nullSafe;
import static com.picsou.export.ProfileExporter.writeInstant;

/**
 * Bank-connection metadata (Enable Banking requisitions).
 *
 * Deliberately excludes the {@code auth_link} (a one-shot OAuth-style URL with
 * a temporary token) — it has no archival value and aligns with the
 * "no security tokens" promise on the README. The exported fields are the
 * institution id/name/status/last_sync, which are what the user actually wants
 * for their own audit trail.
 */
@Component
class BankConnectionsExporter implements EntityExporter {

    private final RequisitionRepository requisitionRepository;

    BankConnectionsExporter(RequisitionRepository requisitionRepository) {
        this.requisitionRepository = requisitionRepository;
    }

    @Override
    public String name() {
        return "bank_connections";
    }

    @Override
    public List<String> csvHeader() {
        return List.of(
            "id", "requisition_id", "institution_id", "institution_name",
            "status", "last_synced_at", "created_at", "updated_at"
        );
    }

    @Override
    public void writeCsv(AppUser user, ExportContext ctx, CsvWriter csv) throws IOException {
        for (Requisition r : requisitionRepository.findAllByMemberId(user.getMember().getId())) {
            csv.writeRow(List.of(
                String.valueOf(r.getId()),
                nullSafe(r.getRequisitionId()),
                nullSafe(r.getInstitutionId()),
                nullSafe(r.getInstitutionName()),
                nullSafe(r.getStatus() == null ? null : r.getStatus().name()),
                r.getLastSyncedAt() == null ? "" : r.getLastSyncedAt().toString(),
                r.getCreatedAt() == null ? "" : r.getCreatedAt().toString(),
                r.getUpdatedAt() == null ? "" : r.getUpdatedAt().toString()
            ));
        }
    }

    @Override
    public void writeJson(AppUser user, ExportContext ctx, JsonGenerator json) throws IOException {
        json.writeStartArray();
        for (Requisition r : requisitionRepository.findAllByMemberId(user.getMember().getId())) {
            json.writeStartObject();
            json.writeNumberField("id", r.getId());
            json.writeStringField("requisition_id", r.getRequisitionId());
            json.writeStringField("institution_id", r.getInstitutionId());
            json.writeStringField("institution_name", r.getInstitutionName());
            json.writeStringField("status", r.getStatus() == null ? null : r.getStatus().name());
            writeInstant(json, "last_synced_at", r.getLastSyncedAt());
            writeInstant(json, "created_at", r.getCreatedAt());
            writeInstant(json, "updated_at", r.getUpdatedAt());
            json.writeEndObject();
        }
        json.writeEndArray();
    }
}
