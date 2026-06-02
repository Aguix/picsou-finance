package com.picsou.export;

import com.fasterxml.jackson.core.JsonGenerator;
import com.picsou.model.AppUser;
import com.picsou.model.WalletAddress;
import com.picsou.repository.WalletAddressRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

import static com.picsou.export.ProfileExporter.nullSafe;
import static com.picsou.export.ProfileExporter.writeInstant;

@Component
class WalletAddressesExporter implements EntityExporter {

    private final WalletAddressRepository walletAddressRepository;

    WalletAddressesExporter(WalletAddressRepository walletAddressRepository) {
        this.walletAddressRepository = walletAddressRepository;
    }

    @Override
    public String name() {
        return "wallet_addresses";
    }

    @Override
    public List<String> csvHeader() {
        return List.of("id", "chain", "address", "label", "last_synced_at", "created_at", "updated_at");
    }

    @Override
    public void writeCsv(AppUser user, ExportContext ctx, CsvWriter csv) throws IOException {
        for (WalletAddress w : walletAddressRepository.findAllByMemberId(user.getMember().getId())) {
            csv.writeRow(List.of(
                String.valueOf(w.getId()),
                nullSafe(w.getChain() == null ? null : w.getChain().name()),
                nullSafe(w.getAddress()),
                nullSafe(w.getLabel()),
                w.getLastSyncedAt() == null ? "" : w.getLastSyncedAt().toString(),
                w.getCreatedAt() == null ? "" : w.getCreatedAt().toString(),
                w.getUpdatedAt() == null ? "" : w.getUpdatedAt().toString()
            ));
        }
    }

    @Override
    public void writeJson(AppUser user, ExportContext ctx, JsonGenerator json) throws IOException {
        json.writeStartArray();
        for (WalletAddress w : walletAddressRepository.findAllByMemberId(user.getMember().getId())) {
            json.writeStartObject();
            json.writeNumberField("id", w.getId());
            json.writeStringField("chain", w.getChain() == null ? null : w.getChain().name());
            json.writeStringField("address", w.getAddress());
            json.writeStringField("label", w.getLabel());
            writeInstant(json, "last_synced_at", w.getLastSyncedAt());
            writeInstant(json, "created_at", w.getCreatedAt());
            writeInstant(json, "updated_at", w.getUpdatedAt());
            json.writeEndObject();
        }
        json.writeEndArray();
    }
}
