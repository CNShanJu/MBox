package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.github.tvbox.osc.config.PrefsDataStore;
import com.github.tvbox.osc.config.SystemConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SslIgnorePolicyTest {
    private static final String SWITCH_KEY = "ignore_ssl_error";
    private static final String LEGACY_HOST_KEY = "ssl_exception_host";

    @Test
    public void switchDefaultsOffAndCanApplyWithoutLegacyHost() throws Exception {
        withIsolatedPrefs(cache -> {
            assertFalse(SystemConfig.isIgnoreSslError());
            cache.put(LEGACY_HOST_KEY, "old.example");
            assertFalse(SystemConfig.isIgnoreSslError());

            cache.put(SWITCH_KEY, true);
            assertTrue(SystemConfig.isIgnoreSslError());
            cache.remove(LEGACY_HOST_KEY);
            assertTrue(SystemConfig.isIgnoreSslError());

            cache.put(SWITCH_KEY, false);
            assertFalse(SystemConfig.isIgnoreSslError());
        });
    }

    @Test
    public void backupOmitsSwitchAndLegacyHostAndImportCannotEnableIt() throws Exception {
        withIsolatedPrefs(cache -> {
            cache.put(SWITCH_KEY, false);
            cache.put(LEGACY_HOST_KEY, "old.example");
            cache.put("home_rec", 1);
            JsonObject exported = JsonParser.parseString(PrefsDataStore.exportJson()).getAsJsonObject();
            assertFalse(exported.has(SWITCH_KEY));
            assertFalse(exported.has(LEGACY_HOST_KEY));
            assertEquals(1, exported.get("home_rec").getAsInt());

            Map<String, Object> imported = new LinkedHashMap<>();
            imported.put(SWITCH_KEY, true);
            imported.put(LEGACY_HOST_KEY, "other.example");
            assertEquals(0, PrefsDataStore.importAll(imported));
            assertFalse(SystemConfig.isIgnoreSslError());
            assertEquals("old.example", cache.get(LEGACY_HOST_KEY));
        });
    }

    private interface PrefsCase {
        void run(ConcurrentHashMap<String, Object> cache) throws Exception;
    }

    private static void withIsolatedPrefs(PrefsCase body) throws Exception {
        synchronized (PrefsDataStore.class) {
            Field field = PrefsDataStore.class.getDeclaredField("cache");
            field.setAccessible(true);
            Object previous = field.get(null);
            ConcurrentHashMap<String, Object> isolated = new ConcurrentHashMap<>();
            try {
                field.set(null, isolated);
                body.run(isolated);
            } finally {
                field.set(null, previous);
            }
        }
    }
}
