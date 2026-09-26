package com.github.tvbox.osc.repo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.github.tvbox.osc.bean.VodInfo;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** CollectRepository 契约测试(Fake 实现) */
public class CollectRepositoryTest {

    static final class FakeCollectRepository implements CollectRepository {
        final Map<String, com.github.tvbox.osc.cache.VodCollect> store = new LinkedHashMap<>();

        @Override
        public void save(String sourceKey, VodInfo vodInfo) {
            String k = sourceKey + "|" + vodInfo.id;
            if (store.containsKey(k)) return;
            com.github.tvbox.osc.cache.VodCollect c = new com.github.tvbox.osc.cache.VodCollect();
            c.sourceKey = sourceKey;
            c.vodId = vodInfo.id;
            c.name = vodInfo.name;
            c.updateTime = System.currentTimeMillis();
            store.put(k, c);
        }

        @Override
        public void delete(String sourceKey, String vodId) {
            store.remove(sourceKey + "|" + vodId);
        }

        @Override
        public void deleteById(int id) {
            store.values().removeIf(c -> c.getId() == id);
        }

        @Override
        public void clear() {
            store.clear();
        }

        @Override
        public boolean isSaved(String sourceKey, String vodId) {
            return store.containsKey(sourceKey + "|" + vodId);
        }

        @Override
        public List<com.github.tvbox.osc.cache.VodCollect> query() {
            return new ArrayList<>(store.values());
        }
    }

    private VodInfo vod(String sourceKey, String id, String name) {
        VodInfo v = new VodInfo();
        v.sourceKey = sourceKey;
        v.id = id;
        v.name = name;
        return v;
    }

    @Before
    public void setUp() {
        HistoryRepositories.setCollect(new FakeCollectRepository());
    }

    @After
    public void tearDown() {
        HistoryRepositories.setCollect(RoomCollectRepository.get());
    }

    @Test
    public void saveIsSaved_query() {
        HistoryRepositories.collect().save("a", vod("a", "1", "收藏1"));
        assertTrue(HistoryRepositories.collect().isSaved("a", "1"));
        assertFalse(HistoryRepositories.collect().isSaved("a", "2"));
        assertEquals(1, HistoryRepositories.collect().query().size());
    }

    @Test
    public void deleteAndClear() {
        HistoryRepositories.collect().save("a", vod("a", "1", "收藏1"));
        HistoryRepositories.collect().save("a", vod("a", "2", "收藏2"));
        HistoryRepositories.collect().delete("a", "1");
        assertFalse(HistoryRepositories.collect().isSaved("a", "1"));
        HistoryRepositories.collect().clear();
        assertTrue(HistoryRepositories.collect().query().isEmpty());
    }
}
