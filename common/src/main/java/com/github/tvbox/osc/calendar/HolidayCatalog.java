package com.github.tvbox.osc.calendar;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Immutable holiday definitions parsed from the app's single holiday JSON asset. */
public final class HolidayCatalog {
    private final List<Entry> holidays;

    private HolidayCatalog(List<Entry> holidays) {
        this.holidays = Collections.unmodifiableList(new ArrayList<>(holidays));
    }

    /** Parses data only; locating or reading the asset belongs to the app layer. */
    public static HolidayCatalog fromJson(String json) {
        if (json == null) throw new IllegalArgumentException("holiday JSON must not be null");
        try {
            JsonObject root = object(JsonParser.parseString(json), "root");
            if (integer(root, "version", "root") != 1) {
                throw new IllegalArgumentException("Unsupported holiday JSON version");
            }
            JsonArray array = array(root, "holidays", "root");
            List<Entry> entries = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < array.size(); i++) {
                String path = "holidays[" + i + "]";
                JsonObject item = object(array.get(i), path);
                String id = string(item, "id", path);
                if (id.isEmpty() || !ids.add(id)) {
                    throw new IllegalArgumentException(path + ".id must be unique and nonempty");
                }
                String name = string(item, "name", path);
                if (name.isEmpty()) throw new IllegalArgumentException(path + ".name is empty");
                String desc = string(item, "desc", path);
                if (desc.trim().isEmpty()) {
                    throw new IllegalArgumentException(path + ".desc is empty");
                }
                String type = string(item, "type", path);
                if (!"solar".equals(type) && !"lunar".equals(type)) {
                    throw new IllegalArgumentException(path + ".type must be solar or lunar");
                }
                int month = integer(item, "month", path);
                int day = integer(item, "day", path);
                int endDay = integer(item, "endDay", path);
                String dateRule = string(item, "dateRule", path);
                String term = string(item, "term", path);
                boolean includeLeapMonth = bool(item, "includeLeapMonth", path);
                boolean display = bool(item, "display", path);
                int displayPriority = integer(item, "displayPriority", path);
                if (month < 1 || month > 12) {
                    throw new IllegalArgumentException(path + ".month must be 1..12");
                }
                if ("fixed".equals(dateRule)) {
                    if (day < 1 || day > 31 || endDay < day || endDay > 31 || !term.isEmpty()) {
                        throw new IllegalArgumentException(path + " has an invalid fixed date range");
                    }
                } else if ("last_day".equals(dateRule)) {
                    if (day != 0 || endDay != 0 || !term.isEmpty()) {
                        throw new IllegalArgumentException(path + " last_day requires day=endDay=0");
                    }
                } else if ("solar_term".equals(dateRule)) {
                    if (!"solar".equals(type) || day != 0 || endDay != 0 || term.isEmpty()) {
                        throw new IllegalArgumentException(path + " has an invalid solar_term rule");
                    }
                } else {
                    throw new IllegalArgumentException(path + ".dateRule is unsupported");
                }
                JsonObject fireworksJson = object(field(item, "fireworks", path), path + ".fireworks");
                boolean fireworksEnabled = bool(fireworksJson, "enabled", path + ".fireworks");
                int maxLaunches = integer(fireworksJson, "maxLaunchesPerDay", path + ".fireworks");
                if (maxLaunches < 0) {
                    throw new IllegalArgumentException(path + ".fireworks.maxLaunchesPerDay must be >= 0");
                }
                int cooldownHours = cooldownHours(fireworksJson);
                boolean cooldownEnabled = bool(fireworksJson, "cooldownEnabled", path + ".fireworks");
                JsonObject splashJson = object(field(item, "splash", path), path + ".splash");
                boolean splashEnabled = bool(splashJson, "enabled", path + ".splash");
                String mediaType = string(splashJson, "mediaType", path + ".splash");
                String asset = string(splashJson, "asset", path + ".splash");
                entries.add(new Entry(id, name, desc, type, month, day, endDay, dateRule, term,
                        includeLeapMonth, display, displayPriority,
                        new Fireworks(fireworksEnabled, maxLaunches, cooldownHours, cooldownEnabled),
                        new Splash(splashEnabled, mediaType, asset)));
            }
            return new HolidayCatalog(entries);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("Malformed holiday JSON", e);
        }
    }

    public List<Entry> getHolidays() { return holidays; }

    /** Returns every matching definition, including non-displayed and non-firework rules. */
    public List<Entry> getMatchingHolidays(int year, int month, int day) {
        boolean lunarSupported = LunarCalendar.supports(year, month, day);
        LunarCalendar.LunarDate lunar = lunarSupported
                ? LunarCalendar.fromGregorian(year, month, day) : null;
        String solarTerm = null;
        boolean solarTermChecked = false;
        List<Entry> matches = new ArrayList<>();
        for (Entry entry : holidays) {
            if ("solar_term".equals(entry.dateRule)) {
                if (month != entry.month) continue;
                if (!solarTermChecked) {
                    solarTerm = SolarTermCalculator.getTermName(year, month, day);
                    solarTermChecked = true;
                }
                if (entry.term.equals(solarTerm)) matches.add(entry);
                continue;
            }
            int effectiveMonth;
            int effectiveDay;
            boolean lastDay;
            if ("lunar".equals(entry.type)) {
                if (lunar == null || (lunar.isLeapMonth() && !entry.includeLeapMonth)) continue;
                effectiveMonth = lunar.getMonth();
                effectiveDay = lunar.getDay();
                lastDay = lunar.isLastDayOfMonth();
            } else {
                effectiveMonth = month;
                effectiveDay = day;
                lastDay = isLastSolarDay(year, month, day);
            }
            if (effectiveMonth != entry.month) continue;
            if ("last_day".equals(entry.dateRule) ? lastDay
                    : effectiveDay >= entry.day && effectiveDay <= entry.endDay) {
                matches.add(entry);
            }
        }
        return Collections.unmodifiableList(matches);
    }

    /** All displayed entries, sorted by descending priority; ties keep JSON order. */
    public List<Entry> getDisplayHolidays(int year, int month, int day) {
        List<Entry> displayed = new ArrayList<>();
        for (Entry entry : getMatchingHolidays(year, month, day)) {
            if (entry.display) displayed.add(entry);
        }
        Collections.sort(displayed, (left, right) -> Integer.compare(
                right.displayPriority, left.displayPriority));
        return Collections.unmodifiableList(displayed);
    }

    /** Compatibility helper for callers needing only the highest-priority name. */
    public Entry getDisplayHoliday(int year, int month, int day) {
        List<Entry> displayed = getDisplayHolidays(year, month, day);
        return displayed.isEmpty() ? null : displayed.get(0);
    }

    /** Finds the first enabled non-solar-term firework rule in JSON order. */
    public Entry getFireworksHoliday(int year, int month, int day) {
        for (Entry entry : getMatchingHolidays(year, month, day)) {
            // Solar terms never launch fireworks, even if their JSON flag was set accidentally.
            if (!"solar_term".equals(entry.dateRule) && entry.fireworks.enabled) return entry;
        }
        return null;
    }

    private static boolean isLastSolarDay(int year, int month, int day) {
        int days;
        switch (month) {
            case 2:
                days = (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) ? 29 : 28;
                break;
            case 4: case 6: case 9: case 11: days = 30; break;
            default: days = 31;
        }
        return day == days;
    }

    private static JsonElement field(JsonObject owner, String key, String path) {
        JsonElement value = owner.get(key);
        if (value == null || value.isJsonNull()) {
            throw new IllegalArgumentException(path + "." + key + " is required");
        }
        return value;
    }

    private static JsonObject object(JsonElement value, String path) {
        if (!value.isJsonObject()) throw new IllegalArgumentException(path + " must be an object");
        return value.getAsJsonObject();
    }

    private static JsonArray array(JsonObject owner, String key, String path) {
        JsonElement value = field(owner, key, path);
        if (!value.isJsonArray()) throw new IllegalArgumentException(path + "." + key + " must be an array");
        return value.getAsJsonArray();
    }

    private static String string(JsonObject owner, String key, String path) {
        JsonElement value = field(owner, key, path);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(path + "." + key + " must be a string");
        }
        return value.getAsString();
    }

    private static boolean bool(JsonObject owner, String key, String path) {
        JsonElement value = field(owner, key, path);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(path + "." + key + " must be a boolean");
        }
        return value.getAsBoolean();
    }

    private static int integer(JsonObject owner, String key, String path) {
        JsonElement value = field(owner, key, path);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(path + "." + key + " must be an integer");
        }
        String number = value.getAsString();
        if (!number.matches("-?(0|[1-9][0-9]*)")) {
            throw new IllegalArgumentException(path + "." + key + " must be an integer");
        }
        try {
            return Integer.parseInt(number);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(path + "." + key + " is out of range", e);
        }
    }

    /** Invalid or out-of-range cooldown values explicitly fall back to one hour. */
    private static int cooldownHours(JsonObject owner) {
        JsonElement value = owner.get("cooldownHours");
        if (value == null || value.isJsonNull()) return 1;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return 1;
        String number = value.getAsString();
        if (!number.matches("-?(0|[1-9][0-9]*)")) return 1;
        try {
            int hours = Integer.parseInt(number);
            return hours >= 1 && hours <= 23 ? hours : 1;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    public static final class Entry {
        private final String id;
        private final String name;
        private final String desc;
        private final String type;
        private final int month;
        private final int day;
        private final int endDay;
        private final String dateRule;
        private final String term;
        private final boolean includeLeapMonth;
        private final boolean display;
        private final int displayPriority;
        private final Fireworks fireworks;
        private final Splash splash;

        private Entry(String id, String name, String desc, String type, int month, int day, int endDay,
                      String dateRule, String term, boolean includeLeapMonth, boolean display,
                      int displayPriority, Fireworks fireworks, Splash splash) {
            this.id = id;
            this.name = name;
            this.desc = desc;
            this.type = type;
            this.month = month;
            this.day = day;
            this.endDay = endDay;
            this.dateRule = dateRule;
            this.term = term;
            this.includeLeapMonth = includeLeapMonth;
            this.display = display;
            this.displayPriority = displayPriority;
            this.fireworks = fireworks;
            this.splash = splash;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public String getDesc() { return desc; }
        public String getType() { return type; }
        public int getMonth() { return month; }
        public int getDay() { return day; }
        public int getEndDay() { return endDay; }
        public String getDateRule() { return dateRule; }
        public String getTerm() { return term; }
        public boolean isIncludeLeapMonth() { return includeLeapMonth; }
        public boolean isDisplay() { return display; }
        public int getDisplayPriority() { return displayPriority; }
        public Fireworks getFireworks() { return fireworks; }
        public Splash getSplash() { return splash; }
    }

    public static final class Fireworks {
        private final boolean enabled;
        private final int maxLaunchesPerDay;
        private final int cooldownHours;
        private final boolean cooldownEnabled;

        private Fireworks(boolean enabled, int maxLaunchesPerDay, int cooldownHours,
                          boolean cooldownEnabled) {
            this.enabled = enabled;
            this.maxLaunchesPerDay = maxLaunchesPerDay;
            this.cooldownHours = cooldownHours;
            this.cooldownEnabled = cooldownEnabled;
        }

        public boolean isEnabled() { return enabled; }
        /** Zero means unlimited launches. */
        public int getMaxLaunchesPerDay() { return maxLaunchesPerDay; }
        public int getCooldownHours() { return cooldownHours; }
        public boolean isCooldownEnabled() { return cooldownEnabled; }
    }

    /** Reserved metadata for future holiday-specific splash selection. */
    public static final class Splash {
        private final boolean enabled;
        private final String mediaType;
        private final String asset;

        private Splash(boolean enabled, String mediaType, String asset) {
            this.enabled = enabled;
            this.mediaType = mediaType;
            this.asset = asset;
        }

        public boolean isEnabled() { return enabled; }
        public String getMediaType() { return mediaType; }
        public String getAsset() { return asset; }
    }
}
