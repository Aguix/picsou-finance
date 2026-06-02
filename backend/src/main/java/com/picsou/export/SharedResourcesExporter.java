package com.picsou.export;

import com.fasterxml.jackson.core.JsonGenerator;
import com.picsou.model.AppUser;
import com.picsou.model.SharedResource;
import com.picsou.repository.SharedResourceRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

@Component
class SharedResourcesExporter implements EntityExporter {

    private static final List<String> KNOWN_TYPES = List.of("ACCOUNT", "GOAL", "DEBT", "WALLET");

    private final SharedResourceRepository sharedResourceRepository;

    SharedResourcesExporter(SharedResourceRepository sharedResourceRepository) {
        this.sharedResourceRepository = sharedResourceRepository;
    }

    @Override
    public String name() {
        return "shared_resources";
    }

    @Override
    public List<String> csvHeader() {
        return List.of("id", "resource_type", "resource_id", "created_at");
    }

    @Override
    public void writeCsv(AppUser user, ExportContext ctx, CsvWriter csv) throws IOException {
        for (SharedResource s : sharedFor(user)) {
            csv.writeRow(List.of(
                String.valueOf(s.getId()),
                s.getResourceType() == null ? "" : s.getResourceType(),
                s.getResourceId() == null ? "" : String.valueOf(s.getResourceId()),
                s.getCreatedAt() == null ? "" : s.getCreatedAt().toString()
            ));
        }
    }

    @Override
    public void writeJson(AppUser user, ExportContext ctx, JsonGenerator json) throws IOException {
        json.writeStartArray();
        for (SharedResource s : sharedFor(user)) {
            json.writeStartObject();
            json.writeNumberField("id", s.getId());
            json.writeStringField("resource_type", s.getResourceType());
            if (s.getResourceId() != null) json.writeNumberField("resource_id", s.getResourceId());
            else json.writeNullField("resource_id");
            json.writeStringField("created_at", s.getCreatedAt() == null ? null : s.getCreatedAt().toString());
            json.writeEndObject();
        }
        json.writeEndArray();
    }

    private List<SharedResource> sharedFor(AppUser user) {
        Long memberId = user.getMember().getId();
        return KNOWN_TYPES.stream()
            .flatMap(t -> sharedResourceRepository.findAllByOwnerMemberIdAndResourceType(memberId, t).stream())
            .toList();
    }
}
