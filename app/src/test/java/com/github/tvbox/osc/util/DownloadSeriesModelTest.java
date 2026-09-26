package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.github.tvbox.osc.bean.VodInfo;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** DownloadSeriesModel(下载弹窗选集数据模型纯逻辑)单测 */
public class DownloadSeriesModelTest {

    private static VodInfo.VodSeries series(String name, boolean selected) {
        VodInfo.VodSeries s = new VodInfo.VodSeries();
        s.name = name;
        s.selected = selected;
        return s;
    }

    private static List<VodInfo.VodSeries> shown(boolean s1, boolean s2, boolean s3) {
        List<VodInfo.VodSeries> list = new ArrayList<>();
        list.add(series("第1集", s1));
        list.add(series("第2集", s2));
        list.add(series("第3集", s3));
        return list;
    }

    @Test
    public void collectSelectedNames_keepsOnlySelectedWithName() {
        Set<String> names = DownloadSeriesModel.collectSelectedNames(shown(true, false, true));
        assertEquals(2, names.size());
        assertTrue(names.contains("第1集"));
        assertTrue(names.contains("第3集"));
        assertFalse(names.contains("第2集"));
        assertTrue(DownloadSeriesModel.collectSelectedNames(null).isEmpty());
    }

    @Test
    public void rebuildCopy_preservesSelectionByMasterAndWritesEpisodeIds() {
        List<VodInfo.VodSeries> master = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            VodInfo.VodSeries s = new VodInfo.VodSeries();
            s.name = "第" + i + "集";
            s.url = "http://e/" + i;
            master.add(s);
        }
        // 用户勾选了"第1集"与"第3集"(当前展示顺序可能不同,按集名匹配)
        Set<String> selected = DownloadSeriesModel.collectSelectedNames(shown(true, false, true));
        List<VodInfo.VodSeries> copy = DownloadSeriesModel.rebuildCopy(master, selected,
                idx -> "src|vod|flag|" + idx);
        assertEquals(3, copy.size());
        assertTrue(copy.get(0).selected);
        assertFalse(copy.get(1).selected);
        assertTrue(copy.get(2).selected);
        // episodeId 按副本内索引生成
        assertEquals("src|vod|flag|0", copy.get(0).episodeId);
        assertEquals("src|vod|flag|2", copy.get(2).episodeId);
        assertEquals("http://e/2", copy.get(1).url);
    }

    @Test
    public void rebuildCopy_nullMasterYieldsEmpty() {
        assertTrue(DownloadSeriesModel.rebuildCopy(null, new java.util.HashSet<>(), i -> "").isEmpty());
    }

    @Test
    public void episodeIdsAndNames_followCopyOrder() {
        List<VodInfo.VodSeries> copy = DownloadSeriesModel.rebuildCopy(shown(false, false, false),
                new java.util.HashSet<>(), idx -> "e" + idx);
        String[] ids = DownloadSeriesModel.episodeIdsOf(copy);
        String[] names = DownloadSeriesModel.episodeNamesOf(copy);
        assertEquals(3, ids.length);
        assertEquals("e2", ids[2]);
        assertEquals("第3集", names[2]);
    }
}
