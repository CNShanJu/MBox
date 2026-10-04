package com.github.tvbox.osc.calendar;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Loads the packaged JSON so policy tests exercise the shipped holiday rules. */
final class HolidayCatalogTestFixture {
    private HolidayCatalogTestFixture() {
    }

    static HolidayCatalog load() {
        return HolidayCatalog.fromJson(json());
    }

    static String json() {
        Path asset = Paths.get("src/main/assets/calendar/holidays.json");
        if (!Files.exists(asset)) asset = Paths.get("app/src/main/assets/calendar/holidays.json");
        try {
            return new String(Files.readAllBytes(asset), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("Unable to read packaged holiday catalog at " + asset, e);
        }
    }
}
