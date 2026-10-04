package com.github.tvbox.osc.calendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class HolidayCatalogAssetTest {
    private static final HolidayCatalog CATALOG = HolidayCatalogTestFixture.load();

    @Test
    public void packagedDefinitionsHaveDescriptionsAndAllSolarTerms() {
        assertEquals(42, CATALOG.getHolidays().size());
        JsonObject root = JsonParser.parseString(HolidayCatalogTestFixture.json()).getAsJsonObject();
        JsonObject fieldDescriptions = root.getAsJsonObject("desc");
        assertNotNull(fieldDescriptions);
        assertEquals(new HashSet<>(Arrays.asList("version", "holidays", "id", "name",
                "entry.desc", "type", "month", "day", "endDay", "dateRule", "term",
                "includeLeapMonth", "display", "displayPriority", "fireworks.enabled",
                "fireworks.maxLaunchesPerDay", "fireworks.cooldownHours",
                "fireworks.cooldownEnabled", "splash.enabled", "splash.mediaType",
                "splash.asset", "rules")), fieldDescriptions.keySet());
        fieldDescriptions.entrySet().forEach(field -> assertFalse(
                field.getKey() + " has no explanation", field.getValue().getAsString().trim().isEmpty()));
        JsonArray rawEntries = root.getAsJsonArray("holidays");
        Set<String> entryFields = rawEntries.get(0).getAsJsonObject().keySet();
        Set<String> fireworksFields = rawEntries.get(0).getAsJsonObject()
                .getAsJsonObject("fireworks").keySet();
        Set<String> splashFields = rawEntries.get(0).getAsJsonObject()
                .getAsJsonObject("splash").keySet();
        assertEquals(new HashSet<>(Arrays.asList("id", "name", "desc", "type", "month", "day",
                "endDay", "dateRule", "term", "includeLeapMonth", "display",
                "displayPriority", "fireworks", "splash")), entryFields);
        assertEquals(new HashSet<>(Arrays.asList("enabled", "maxLaunchesPerDay",
                "cooldownHours", "cooldownEnabled")), fireworksFields);
        assertEquals(new HashSet<>(Arrays.asList("enabled", "mediaType", "asset")), splashFields);
        for (int i = 0; i < rawEntries.size(); i++) {
            JsonObject raw = rawEntries.get(i).getAsJsonObject();
            assertEquals("entry fields differ at " + i, entryFields, raw.keySet());
            assertEquals("fireworks fields differ at " + i, fireworksFields,
                    raw.getAsJsonObject("fireworks").keySet());
            assertEquals("splash fields differ at " + i, splashFields,
                    raw.getAsJsonObject("splash").keySet());
        }
        Set<String> terms = new HashSet<>();
        for (HolidayCatalog.Entry holiday : CATALOG.getHolidays()) {
            assertFalse(holiday.getId() + " has no desc", holiday.getDesc().trim().isEmpty());
            assertNotNull(holiday.getFireworks());
            assertNotNull(holiday.getSplash());
            if ("solar_term".equals(holiday.getDateRule())) {
                assertEquals("solar", holiday.getType());
                assertTrue(holiday.isDisplay());
                assertFalse(holiday.getFireworks().isEnabled());
                terms.add(holiday.getTerm());
            }
        }
        assertEquals(24, terms.size());
        assertTrue(terms.contains("清明"));
        assertTrue(terms.contains("冬至"));
        assertEquals("寒露", CATALOG.getDisplayHoliday(2026, 10, 8).getName());
    }

    @Test
    public void packagedNationalDayFireworksIgnoreLunarDisplayPriority() {
        assertEquals("中秋节", CATALOG.getDisplayHoliday(2020, 10, 1).getName());
        assertEquals("中秋节·国庆节",
                CalendarDayInfo.from(2020, 10, 1, CATALOG).getHolidayText());
        HolidayCatalog.Entry fireworks = CATALOG.getFireworksHoliday(2020, 10, 1);
        assertEquals("国庆节", fireworks.getName());
        assertEquals(3, fireworks.getFireworks().getMaxLaunchesPerDay());
        assertEquals(2, fireworks.getFireworks().getCooldownHours());
    }
}
