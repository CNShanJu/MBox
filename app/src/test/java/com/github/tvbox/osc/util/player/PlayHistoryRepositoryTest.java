package com.github.tvbox.osc.util.player;

import static org.junit.Assert.assertEquals;

import com.github.tvbox.osc.repo.CacheRepository;
import com.github.tvbox.osc.repo.HistoryRepositories;
import com.github.tvbox.osc.repo.RoomCacheRepository;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/** PlayHistoryRepository 契约测试(Fake CacheRepository,行为与迁出前内嵌逻辑等价) */
public class PlayHistoryRepositoryTest {

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

    private final PlayHistoryRepository repo = new PlayHistoryRepository();
    private FakeCacheRepository fake;

    @Before
    public void setUp() {
        fake = new FakeCacheRepository();
        HistoryRepositories.setCache(fake);
    }

    @After
    public void tearDown() {
        HistoryRepositories.setCache(RoomCacheRepository.get());
    }

    @Test
    public void saveLoad_roundTrip() {
        repo.save("vod-1-ep-3", 12_345L);
        assertEquals(12_345L, repo.load("vod-1-ep-3", 0L));
    }

    @Test
    public void missing_returnsSkipStart() {
        assertEquals(8_000L, repo.load("no-such-key", 8_000L));
    }

    @Test
    public void load_takesMaxOfCachedAndSkipStart() {
        repo.save("k", 5_000L);
        assertEquals(8_000L, repo.load("k", 8_000L));
        repo.save("k2", 9_000L);
        assertEquals(9_000L, repo.load("k2", 2_000L));
    }

    @Test
    public void load_acceptsStringCacheValue() {
        fake.store.put(com.github.tvbox.osc.util.MD5.string2MD5("k"), "6000");
        assertEquals(6_000L, repo.load("k", 0L));
    }

    @Test
    public void load_unparsableStringFallsBackToSkipStart() {
        fake.store.put(com.github.tvbox.osc.util.MD5.string2MD5("k"), "not-a-number");
        assertEquals(3_000L, repo.load("k", 3_000L));
    }

    @Test
    public void load_nonNumericTypeFallsBackToSkipStart() {
        fake.store.put(com.github.tvbox.osc.util.MD5.string2MD5("k"), 123); // Integer 非 Long/String
        assertEquals(0L, repo.load("k", 0L));
    }

    @Test
    public void delete_removesProgress() {
        repo.save("k", 100L);
        repo.delete("k");
        assertEquals(0L, repo.load("k", 0L));
    }
}
