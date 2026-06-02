package com.picsou.export;

import com.fasterxml.jackson.core.JsonGenerator;
import com.picsou.model.AppUser;
import com.picsou.model.FamilyMember;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * Exports the user's profile: their AppUser identity (no password hash,
 * no activation token) and their owned FamilyMember row.
 */
@Component
class ProfileExporter implements EntityExporter {

    @Override
    public String name() {
        return "profile";
    }

    @Override
    public List<String> csvHeader() {
        return List.of(
            "user_id", "username", "role", "activated", "acknowledged_warning",
            "created_at", "updated_at",
            "member_id", "display_name", "avatar_color", "managed"
        );
    }

    @Override
    public void writeCsv(AppUser user, ExportContext ctx, CsvWriter csv) throws IOException {
        FamilyMember m = user.getMember();
        csv.writeRow(List.of(
            String.valueOf(user.getId()),
            nullSafe(user.getUsername()),
            nullSafe(user.getRole() == null ? null : user.getRole().name()),
            String.valueOf(user.isActivated()),
            String.valueOf(user.isAcknowledgedWarning()),
            nullSafe(user.getCreatedAt() == null ? null : user.getCreatedAt().toString()),
            nullSafe(user.getUpdatedAt() == null ? null : user.getUpdatedAt().toString()),
            String.valueOf(m.getId()),
            nullSafe(m.getDisplayName()),
            nullSafe(m.getAvatarColor()),
            String.valueOf(m.isManaged())
        ));
    }

    @Override
    public void writeJson(AppUser user, ExportContext ctx, JsonGenerator json) throws IOException {
        FamilyMember m = user.getMember();
        json.writeStartObject();
        json.writeNumberField("user_id", user.getId());
        json.writeStringField("username", user.getUsername());
        json.writeStringField("role", user.getRole() == null ? null : user.getRole().name());
        json.writeBooleanField("activated", user.isActivated());
        json.writeBooleanField("acknowledged_warning", user.isAcknowledgedWarning());
        writeInstant(json, "created_at", user.getCreatedAt());
        writeInstant(json, "updated_at", user.getUpdatedAt());
        json.writeObjectFieldStart("member");
        json.writeNumberField("id", m.getId());
        json.writeStringField("display_name", m.getDisplayName());
        json.writeStringField("avatar_color", m.getAvatarColor());
        json.writeBooleanField("managed", m.isManaged());
        json.writeEndObject();
        json.writeEndObject();
    }

    static void writeInstant(JsonGenerator json, String field, java.time.Instant instant) throws IOException {
        if (instant == null) json.writeNullField(field);
        else json.writeStringField(field, instant.toString());
    }

    static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
