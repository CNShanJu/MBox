package com.github.tvbox.osc.transfer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Compatibility transformations for settings in full local backups. */
public final class BackupSettingsCompat {
    private static final String LEGACY_VIDEO_PURIFY = "video_purify";
    private static final String VIDEO_PURIFY_MODE = "video_purify_mode";

    private BackupSettingsCompat() { }

    /** Restore the old boolean choice while rejecting invalid mode values from backup data. */
    public static String migrateLegacyVideoPurify(String json) {
        JsonObject settings = JsonParser.parseString(json).getAsJsonObject();
        JsonElement mode = settings.get(VIDEO_PURIFY_MODE);
        if (validMode(mode)) return json;
        JsonElement legacy = settings.get(LEGACY_VIDEO_PURIFY);
        if (legacy != null && legacy.isJsonPrimitive() && legacy.getAsJsonPrimitive().isBoolean()) {
            settings.addProperty(VIDEO_PURIFY_MODE, legacy.getAsBoolean() ? 1 : 0);
        } else if (mode != null) {
            settings.remove(VIDEO_PURIFY_MODE);
        } else {
            return json;
        }
        return settings.toString();
    }

    private static boolean validMode(JsonElement mode) {
        if (mode == null || !mode.isJsonPrimitive() || !mode.getAsJsonPrimitive().isNumber()) return false;
        String raw = mode.getAsString();
        return "0".equals(raw) || "1".equals(raw) || "2".equals(raw);
    }
}
