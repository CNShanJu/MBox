package com.github.tvbox.osc.ui.dialog;

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
