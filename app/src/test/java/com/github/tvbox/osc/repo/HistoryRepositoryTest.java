package com.github.tvbox.osc.repo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.github.tvbox.osc.bean.VodInfo;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** HistoryRepository 契约测试:注入 Fake 仓储,验证 UI 可依赖接口与静态访问点 */
public class HistoryRepositoryTest {

    /** 内存 Fake:验证契约语义,不依赖 Room/Android */
    static final class FakeHistoryRepository implements HistoryRepository {
        final Map<String, VodInfo> store = new LinkedHashMap<>();

        private static String key(String sourceKey, String vodId) {
            return sourceKey + "|" + vodId;
        }

        @Override
        public void save(String sourceKey, VodInfo vodInfo) {
            store.put(key(sourceKey, vodInfo.id), vodInfo);
        }

        @Override
        public void delete(String sourceKey, String vodId) {
            store.remove(key(sourceKey, vodId));
        }

        @Override
        public void clear() {
            store.clear();
        }

        @Override
        public List<VodInfo> query(int limit, Predicate<String> sourceExists, int historyTrimLimit) {
            List<VodInfo> out = new ArrayList<>();
            for (VodInfo v : store.values()) {
                if (sourceExists != null && !sourceExists.test(v.sourceKey)) continue;
                out.add(v);
                if (limit > 0 && out.size() >= limit) break;
            }
            return out;
        }

        @Override
        public VodInfo get(String sourceKey, String vodId) {
            return store.get(key(sourceKey, vodId));
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
        HistoryRepositories.setHistory(new FakeHistoryRepository());
    }

    @After
    public void tearDown() {
        HistoryRepositories.setHistory(RoomHistoryRepository.get());
    }

    @Test
    public void saveAndQuery_returnsEntries() {
        HistoryRepositories.history().save("srcA", vod("srcA", "1", "影片1"));
        HistoryRepositories.history().save("srcA", vod("srcA", "2", "影片2"));
        List<VodInfo> all = HistoryRepositories.history().query(100, null, 0);
        assertEquals(2, all.size());
    }

    @Test
    public void delete_removesSingle() {
        HistoryRepositories.history().save("srcA", vod("srcA", "1", "影片1"));
        HistoryRepositories.history().save("srcA", vod("srcA", "2", "影片2"));
        HistoryRepositories.history().delete("srcA", "1");
        List<VodInfo> all = HistoryRepositories.history().query(100, null, 0);
        assertEquals(1, all.size());
        assertEquals("2", all.get(0).id);
    }

    @Test
    public void clear_empties() {
        HistoryRepositories.history().save("srcA", vod("srcA", "1", "影片1"));
        HistoryRepositories.history().clear();
        assertTrue(HistoryRepositories.history().query(100, null, 0).isEmpty());
    }

    @Test
    public void predicate_filtersBySource() {
        HistoryRepositories.history().save("srcA", vod("srcA", "1", "影片1"));
        HistoryRepositories.history().save("gone", vod("gone", "9", "失效源影片"));
        List<VodInfo> filtered = HistoryRepositories.history().query(100,
                sourceKey -> "srcA".equals(sourceKey), 0);
        assertEquals(1, filtered.size());
        assertNotNull(filtered.get(0));
    }

    @Test
    public void get_bySourceAndVodId() {
        HistoryRepositories.history().save("srcA", vod("srcA", "1", "影片1"));
        VodInfo hit = HistoryRepositories.history().get("srcA", "1");
        assertNotNull(hit);
        assertEquals("影片1", hit.name);
        assertTrue(HistoryRepositories.history().get("srcA", "nope") == null);
        assertTrue(HistoryRepositories.history().get("other", "1") == null);
    }
}
