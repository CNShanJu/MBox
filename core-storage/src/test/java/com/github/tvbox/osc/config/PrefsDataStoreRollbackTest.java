package com.github.tvbox.osc.config;

import androidx.datastore.preferences.core.MutablePreferences;
import androidx.datastore.preferences.core.Preferences;
import androidx.datastore.preferences.core.PreferencesFactory;
import androidx.datastore.preferences.core.PreferencesKeys;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

public class PrefsDataStoreRollbackTest {
    private Field editorField;
    private Field cacheField;
    private Object oldEditor;
    private Object oldCache;
    private FakeStore disk;

    @Before
    public void installFakeStore() throws Exception {
        editorField = PrefsDataStore.class.getDeclaredField("editor");
        cacheField = PrefsDataStore.class.getDeclaredField("cache");
        editorField.setAccessible(true);
        cacheField.setAccessible(true);
        oldEditor = editorField.get(null);
        oldCache = cacheField.get(null);
        Map<String, Object> initial = new LinkedHashMap<>();
        initial.put("changed", "before");
        initial.put("deleted", 7);
        initial.put("untouched", "keep");
        initial.put("lan_pairing_code", "local-secret");
        disk = new FakeStore(initial);
        cacheField.set(null, new ConcurrentHashMap<>(initial));
        editorField.set(null, disk);
    }

    @After
    public void restoreStore() throws Exception {
        editorField.set(null, oldEditor);
        cacheField.set(null, oldCache);
    }

    @Test
    public void failedScopeRestoresChangedAndDeletedKeysAndRemovesNewKeysInOneEdit() {
        Map<String, Object> before = disk.snapshot();
        IOException failure = assertThrows(IOException.class, () -> PrefsDataStore.runWithRollback(() -> {
            PrefsDataStore.put("changed", "after");
            PrefsDataStore.put("added", true);
            PrefsDataStore.delete("deleted");
            Map<String, Object> imported = new LinkedHashMap<>();
            imported.put("batch", 12L);
            imported.put("lan_pairing_code", "remote-secret");
            assertEquals(1, PrefsDataStore.importAll(imported));
            throw new IOException("later category failed");
        }));

        assertEquals("later category failed", failure.getMessage());
        assertEquals(before, disk.snapshot());
        assertEquals(5, disk.updates); // Four writes plus one atomic rollback edit.
        assertEquals("before", PrefsDataStore.getString("changed", ""));
        assertEquals(7, PrefsDataStore.getInt("deleted", -1));
        assertFalse(PrefsDataStore.contains("added"));
        assertFalse(PrefsDataStore.contains("batch"));
        assertEquals("local-secret", PrefsDataStore.getString("lan_pairing_code", ""));
    }

    @Test
    public void persistenceFailureInsideScopePropagatesAndRestoresEarlierWrite() {
        disk.failOn(2);
        RuntimeException failure = assertThrows(RuntimeException.class, () -> PrefsDataStore.runWithRollback(() -> {
            PrefsDataStore.put("changed", "after");
            PrefsDataStore.importAll(java.util.Collections.singletonMap("added", "new"));
            return null;
        }));

        assertTrue(failure.getMessage().contains("injected write 2"));
        assertEquals("before", PrefsDataStore.getString("changed", ""));
        assertFalse(PrefsDataStore.contains("added"));
        assertEquals("before", disk.snapshot().get("changed"));
        assertFalse(disk.snapshot().containsKey("added"));
        assertEquals(3, disk.updates);
    }

    @Test
    public void failedPutAndDeleteInsideScopeBothThrowInsteadOfBeingSwallowed() {
        disk.failOn(1, 3);
        RuntimeException putFailure = assertThrows(RuntimeException.class, () ->
                PrefsDataStore.runWithRollback(() -> {
                    PrefsDataStore.put("changed", "after");
                    return null;
                }));
        RuntimeException deleteFailure = assertThrows(RuntimeException.class, () ->
                PrefsDataStore.runWithRollback(() -> {
                    PrefsDataStore.delete("deleted");
                    return null;
                }));

        assertTrue(putFailure.getMessage().contains("injected write 1"));
        assertTrue(deleteFailure.getMessage().contains("injected write 3"));
        assertEquals("before", PrefsDataStore.getString("changed", ""));
        assertEquals(7, PrefsDataStore.getInt("deleted", -1));
        assertEquals(Integer.valueOf(7), disk.snapshot().get("deleted"));
        assertEquals(4, disk.updates); // Each failed write is followed by one restore edit.
    }

    @Test
    public void successfulScopeKeepsSynchronousWritesAndReturnsWorkResult() throws Exception {
        String result = PrefsDataStore.runWithRollback(() -> {
            PrefsDataStore.put("changed", "committed");
            return "done";
        });

        assertEquals("done", result);
        assertEquals("committed", PrefsDataStore.getString("changed", ""));
        assertEquals("committed", disk.snapshot().get("changed"));
        assertEquals(1, disk.updates);
    }

    @Test
    public void rollbackFailureIsAttachedToOriginalFailureAndNeverReportedAsRestored() {
        disk.failOn(2);
        IOException original = new IOException("database commit failed");
        IOException failure = assertThrows(IOException.class, () -> PrefsDataStore.runWithRollback(() -> {
            PrefsDataStore.put("changed", "after");
            throw original;
        }));

        assertSame(original, failure);
        assertEquals(1, failure.getSuppressed().length);
        assertTrue(failure.getSuppressed()[0].getMessage().contains("回滚失败"));
        assertEquals("after", disk.snapshot().get("changed"));
        assertEquals("after", PrefsDataStore.getString("changed", ""));
    }

    @Test
    public void ordinaryWritesKeepTheirExistingFailureReturnBehavior() {
        disk.failOn(1, 2, 4);
        PrefsDataStore.put("changed", "failed");
        assertEquals("before", PrefsDataStore.getString("changed", ""));
        assertEquals(-1, PrefsDataStore.importAll(java.util.Collections.singletonMap("added", "new")));
        assertFalse(PrefsDataStore.contains("added"));
        PrefsDataStore.put("changed", "saved");
        PrefsDataStore.delete("changed");
        assertEquals("saved", PrefsDataStore.getString("changed", ""));
        assertEquals("saved", disk.snapshot().get("changed"));
    }

    @Test
    public void replaceTransferableJsonRemovesKeysAddedAfterSnapshot() throws Exception {
        String before = PrefsDataStore.exportJson();
        PrefsDataStore.put("added_later", "temporary");
        PrefsDataStore.put("changed", "newer");
        Object oldCache = cacheField.get(null);

        assertEquals(3, PrefsDataStore.replaceTransferableJson(before));

        assertNotSame(oldCache, cacheField.get(null));
        assertFalse(PrefsDataStore.contains("added_later"));
        assertFalse(disk.snapshot().containsKey("added_later"));
        assertEquals("before", PrefsDataStore.getString("changed", ""));
        assertEquals("before", disk.snapshot().get("changed"));
    }

    @Test
    public void replaceTransferableJsonPreservesLocalPrivateKeys() {
        PrefsDataStore.put("_private_device", "device-secret");
        PrefsDataStore.put(SystemConfig.KEY_IGNORE_SSL_ERROR, true);
        PrefsDataStore.put("ssl_exception_host", "old-host");

        assertEquals(1, PrefsDataStore.replaceTransferableJson("{\"changed\":\"restored\","
                + "\"lan_pairing_code\":\"remote-secret\","
                + "\"_private_device\":\"remote-device\","
                + "\"ignore_ssl_error\":false,"
                + "\"ssl_exception_host\":\"remote-host\"}"));

        assertEquals("restored", PrefsDataStore.getString("changed", ""));
        assertFalse(PrefsDataStore.contains("deleted"));
        assertEquals("local-secret", PrefsDataStore.getString("lan_pairing_code", ""));
        assertEquals("device-secret", PrefsDataStore.getString("_private_device", ""));
        assertTrue(PrefsDataStore.getBoolean(SystemConfig.KEY_IGNORE_SSL_ERROR, false));
        assertEquals("old-host", PrefsDataStore.getString("ssl_exception_host", ""));
        assertEquals("device-secret", disk.snapshot().get("_private_device"));
        assertEquals(Boolean.TRUE, disk.snapshot().get(SystemConfig.KEY_IGNORE_SSL_ERROR));
    }

    @Test
    public void emptyObjectClearsOnlyTransferableKeys() {
        assertEquals(0, PrefsDataStore.replaceTransferableJson("{}"));

        assertFalse(PrefsDataStore.contains("changed"));
        assertFalse(PrefsDataStore.contains("deleted"));
        assertFalse(PrefsDataStore.contains("untouched"));
        assertEquals("local-secret", PrefsDataStore.getString("lan_pairing_code", ""));
        assertEquals(java.util.Collections.singletonMap("lan_pairing_code", "local-secret"), disk.snapshot());
        assertEquals(1, disk.updates);
    }

    @Test
    public void failedReplaceKeepsDiskAndCacheUnchangedAndThrows() throws Exception {
        Map<String, Object> before = disk.snapshot();
        Object oldCache = cacheField.get(null);
        disk.failOn(1);

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> PrefsDataStore.replaceTransferableJson("{\"changed\":\"after\"}"));

        assertTrue(failure.getMessage().contains("injected write 1"));
        assertSame(oldCache, cacheField.get(null));
        assertEquals(before, disk.snapshot());
        assertEquals("before", PrefsDataStore.getString("changed", ""));
        assertEquals(7, PrefsDataStore.getInt("deleted", -1));
        assertFalse(PrefsDataStore.contains("added"));
    }

    @Test
    public void replaceInsideRollbackScopeRestoresDeletedAndOverwrittenKeys() {
        Map<String, Object> before = disk.snapshot();
        IOException failure = assertThrows(IOException.class, () -> PrefsDataStore.runWithRollback(() -> {
            assertEquals(2, PrefsDataStore.replaceTransferableJson("{\"changed\":\"after\",\"added\":true}"));
            assertFalse(PrefsDataStore.contains("deleted"));
            throw new IOException("later failure");
        }));

        assertEquals("later failure", failure.getMessage());
        assertEquals(before, disk.snapshot());
        assertEquals("before", PrefsDataStore.getString("changed", ""));
        assertEquals(7, PrefsDataStore.getInt("deleted", -1));
        assertEquals("keep", PrefsDataStore.getString("untouched", ""));
        assertFalse(PrefsDataStore.contains("added"));
        assertEquals(2, disk.updates);
    }

    @Test
    public void unsupportedValueCannotClearCurrentSettings() {
        Map<String, Object> before = disk.snapshot();
        assertThrows(IllegalArgumentException.class,
                () -> PrefsDataStore.replaceTransferableJson("{\"changed\":null}"));
        assertEquals(before, disk.snapshot());
        assertEquals(0, disk.updates);
    }

    @Test
    public void exportJsonOmitsAllLogSettings() {
        seedLocalLogSettings();

        com.google.gson.JsonObject backup = com.google.gson.JsonParser.parseString(
                PrefsDataStore.exportJson()).getAsJsonObject();

        assertTrue(backup.has("changed"));
        assertFalse(backup.has("app_log"));
        assertFalse(backup.has("log_level"));
        assertFalse(backup.has("log_retention"));
        assertLocalLogSettings();
    }

    @Test
    public void importJsonIgnoresLogSettingsFromOldBackup() {
        seedLocalLogSettings();

        assertEquals(1, PrefsDataStore.importJson("{\"changed\":\"imported\","
                + "\"app_log\":false,\"log_level\":3,\"log_retention\":1}"));

        assertEquals("imported", PrefsDataStore.getString("changed", ""));
        assertEquals("imported", disk.snapshot().get("changed"));
        assertLocalLogSettings();
    }

    @Test
    public void replaceTransferableJsonIgnoresLogSettingsFromSystemBackup() {
        seedLocalLogSettings();

        assertEquals(1, PrefsDataStore.replaceTransferableJson("{\"changed\":\"restored\","
                + "\"app_log\":false,\"log_level\":3,\"log_retention\":1}"));

        assertEquals("restored", PrefsDataStore.getString("changed", ""));
        assertFalse(PrefsDataStore.contains("deleted"));
        assertFalse(disk.snapshot().containsKey("deleted"));
        assertLocalLogSettings();
    }

    @Test
    public void emptySystemBackupLeavesLogSettingsUntouched() {
        seedLocalLogSettings();

        assertEquals(0, PrefsDataStore.replaceTransferableJson("{}"));

        assertFalse(PrefsDataStore.contains("changed"));
        assertFalse(disk.snapshot().containsKey("changed"));
        assertLocalLogSettings();
    }

    @Test
    public void internalRestartMarkerIsPersistedAndConsumedOnlyOnce() {
        SystemConfig.markInternalRestart();
        assertTrue(SystemConfig.consumeInternalRestart());
        assertFalse(SystemConfig.consumeInternalRestart());
    }

    @Test
    public void internalRestartMarkerFailureThrowsAndLeavesNoLaunchMarker() {
        Map<String, Object> before = disk.snapshot();
        disk.failOn(1);
        assertThrows(IllegalStateException.class, SystemConfig::markInternalRestart);
        assertEquals(before, disk.snapshot());
        assertFalse(SystemConfig.consumeInternalRestart());
    }

    private void seedLocalLogSettings() {
        PrefsDataStore.put("app_log", true);
        PrefsDataStore.put("log_level", 0);
        PrefsDataStore.put("log_retention", 14);
    }

    private void assertLocalLogSettings() {
        assertTrue(PrefsDataStore.getBoolean("app_log", false));
        assertEquals(0, PrefsDataStore.getInt("log_level", -1));
        assertEquals(14, PrefsDataStore.getInt("log_retention", -1));
        assertEquals(Boolean.TRUE, disk.snapshot().get("app_log"));
        assertEquals(Integer.valueOf(0), disk.snapshot().get("log_level"));
        assertEquals(Integer.valueOf(14), disk.snapshot().get("log_retention"));
    }

    private static final class FakeStore implements PrefsDataStore.EditStore {
        private Preferences state;
        private final Set<Integer> failures = new HashSet<>();
        int updates;

        FakeStore(Map<String, Object> initial) {
            MutablePreferences mutable = PreferencesFactory.createMutable();
            for (Map.Entry<String, Object> entry : initial.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String) mutable.set(PreferencesKeys.stringKey(entry.getKey()), (String) value);
                else if (value instanceof Integer) mutable.set(PreferencesKeys.intKey(entry.getKey()), (Integer) value);
            }
            state = mutable.toPreferences();
        }

        void failOn(Integer... attempts) { failures.addAll(Arrays.asList(attempts)); }

        @Override
        public void update(Function<Preferences, MutablePreferences> edit) {
            updates++;
            if (failures.contains(updates)) throw new IllegalStateException("injected write " + updates);
            state = edit.apply(state).toPreferences();
        }

        Map<String, Object> snapshot() {
            Map<String, Object> values = new LinkedHashMap<>();
            for (Map.Entry<Preferences.Key<?>, Object> entry : state.asMap().entrySet()) {
                values.put(entry.getKey().getName(), entry.getValue());
            }
            return values;
        }
    }
}
