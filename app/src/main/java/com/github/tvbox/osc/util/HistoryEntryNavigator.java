package com.github.tvbox.osc.util;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;

import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.ui.activity.DetailActivity;
import com.github.tvbox.osc.ui.activity.FastSearchActivity;

/** 历史卡片与首页“上次看到”共用的打开路径。 */
public final class HistoryEntryNavigator {
    private HistoryEntryNavigator() { }

    public static void open(Context context, VodInfo info) {
        if (context == null || info == null) return;
        if (HistorySourceBinding.isAvailable(info)) {
            context.startActivity(new Intent(context, DetailActivity.class)
                    .putExtra("id", info.id)
                    .putExtra("sourceKey", info.sourceKey)
                    .putExtra("vodName", info.name));
        } else if (!TextUtils.isEmpty(info.name)) {
            AppBubble.toast("原来源未启用，正在当前订阅中搜索同名影片");
            context.startActivity(new Intent(context, FastSearchActivity.class)
                    .putExtra("title", info.name));
        } else {
            AppBubble.toast("原来源未启用，且历史记录缺少片名");
        }
    }
}
