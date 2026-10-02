package com.github.tvbox.osc.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;

public class PrefsBackupCodecTest {
    @Test
    public void preservesLongIntegerAndOtherScalarTypes() {
        Map<String, Object> values = PrefsDataStore.parseImportValues(
                "{\"time\":1760000000123,\"count\":42,\"speed\":1.5,\"on\":true,\"name\":\"MBox\"}");

        assertTrue(values.get("time") instanceof Long);
        assertEquals(1760000000123L, values.get("time"));
        assertTrue(values.get("count") instanceof Integer);
        assertEquals(42, values.get("count"));
        assertTrue(values.get("speed") instanceof Float);
        assertEquals(1.5f, (Float) values.get("speed"), 0f);
        assertEquals(true, values.get("on"));
        assertEquals("MBox", values.get("name"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonObjectBackup() {
        PrefsDataStore.parseImportValues("[1,2,3]");
    }
}
