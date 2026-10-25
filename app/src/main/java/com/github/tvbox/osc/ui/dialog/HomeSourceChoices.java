package com.github.tvbox.osc.ui.dialog;

import com.github.tvbox.osc.bean.SourceBean;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** 首页数据源的不可变展示快照；筛选只改变可见项，不改变来源标识和顺序。 */
public final class HomeSourceChoices {
    private HomeSourceChoices() { }

    public static final class Row {
        public final String key;
        public final String name;

        public Row(String key, String name) {
            this.key = key;
            this.name = name == null ? "" : name;
        }
    }

    /**
     * 像直播源的站点(订阅配置的 sites 数组里混进来的直播条目):
     * ① api 是频道清单文件(.txt/.m3u/.m3u8);
     * ② 名称像直播源(直播/IPTV/live)且 api 为空或同为频道清单地址。
     * <p>直播源有独立页面与切换入口(直播页设置抽屉),首页换源弹窗不展示它们。
     */
    public static boolean isLiveLikeSource(SourceBean bean) {
        if (bean == null) return false;
        String api = bean.getApi() == null ? "" : bean.getApi().trim().toLowerCase(Locale.ROOT);
        if (isChannelListUrl(api)) return true;
        if (api.isEmpty() && isLiveLikeName(bean.getName())) return true;
        return false;
    }

    private static boolean isChannelListUrl(String apiLower) {
        return apiLower.endsWith(".txt") || apiLower.endsWith(".m3u") || apiLower.endsWith(".m3u8");
    }

    private static boolean isLiveLikeName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("直播") || lower.contains("iptv") || lower.contains("live");
    }

    public static List<Row> filter(List<Row> rows, String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Row> result = new ArrayList<>();
        for (Row row : rows) {
            if (row.name.toLowerCase(Locale.ROOT).contains(needle)) result.add(row);
        }
        return Collections.unmodifiableList(result);
    }

    public static int selectedIndex(List<Row> rows, String selectedKey) {
        for (int i = 0; i < rows.size(); i++) {
            if (Objects.equals(rows.get(i).key, selectedKey)) return i;
        }
        return -1;
    }
}
