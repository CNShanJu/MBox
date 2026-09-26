package com.github.tvbox.osc.repo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/** CacheRepository 契约测试(Fake 实现) */
public class CacheRepositoryTest {

    static final class FakeCacheRepository implements CacheRepository {
        final Map<String, Object> store = new LinkedHashMap<>();

        @Override
        public Object get(String key) {
            return store.get(key);
        }

        @Override
        public void save(String key, Object value) {
            store.put(key, value);
        }

        @Override
        public void delete(String key, Object body) {
            store.remove(key);
        }
    }

    @Before
    public void setUp() {
        HistoryRepositories.setCache(new FakeCacheRepository());
    }

    @After
    public void tearDown() {
        HistoryRepositories.setCache(RoomCacheRepository.get());
    }

    @Test
    public void saveGet_roundTrip() {
        HistoryRepositories.cache().save("k1", "v1");
        assertEquals("v1", HistoryRepositories.cache().get("k1"));
    }

    @Test
    public void missing_returnsNull() {
        assertNull(HistoryRepositories.cache().get("nope"));
    }

    @Test
    public void delete_removes() {
        HistoryRepositories.cache().save("k1", "v1");
        HistoryRepositories.cache().delete("k1", "v1");
        assertNull(HistoryRepositories.cache().get("k1"));
    }
}
