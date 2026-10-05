package com.github.tvbox.osc.transfer;

import static org.junit.Assert.assertEquals;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

public class BackupSettingsCompatTest {
    @Test
    public void mapsLegacyBooleanToPreviousFilteringChoice() {
        JsonObject disabled = JsonParser.parseString(BackupSettingsCompat.migrateLegacyVideoPurify(
                "{\"video_purify\":false,\"other\":42}")).getAsJsonObject();
        JsonObject enabled = JsonParser.parseString(BackupSettingsCompat.migrateLegacyVideoPurify(
                "{\"video_purify\":true}")).getAsJsonObject();

        assertEquals(0, disabled.get("video_purify_mode").getAsInt());
        assertEquals(42, disabled.get("other").getAsInt());
        assertEquals(1, enabled.get("video_purify_mode").getAsInt());
    }

    @Test
    public void preservesExplicitModeAndUnrelatedSettings() {
        String explicit = "{\"video_purify\":false,\"video_purify_mode\":2}";
        String unrelated = "{\"other\":true}";
        assertEquals(explicit, BackupSettingsCompat.migrateLegacyVideoPurify(explicit));
        assertEquals(unrelated, BackupSettingsCompat.migrateLegacyVideoPurify(unrelated));
    }

    @Test
    public void invalidModeFallsBackToLegacyBoolean() {
        JsonObject migrated = JsonParser.parseString(BackupSettingsCompat.migrateLegacyVideoPurify(
                "{\"video_purify\":true,\"video_purify_mode\":2.5}")).getAsJsonObject();
        assertEquals(1, migrated.get("video_purify_mode").getAsInt());
    }

    @Test
    public void invalidModeWithoutLegacyBooleanIsRemoved() {
        JsonObject migrated = JsonParser.parseString(BackupSettingsCompat.migrateLegacyVideoPurify(
                "{\"video_purify_mode\":2.5,\"other\":42}")).getAsJsonObject();
        assertEquals(false, migrated.has("video_purify_mode"));
        assertEquals(42, migrated.get("other").getAsInt());
    }
}
