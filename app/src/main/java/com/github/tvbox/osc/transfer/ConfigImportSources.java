package com.github.tvbox.osc.transfer;

import android.content.Context;
import com.github.tvbox.osc.server.LanSyncClient;

/** 配置导入来源装配入口；在线或缓存来源可独立增加实现。 */
public final class ConfigImportSources {
    private ConfigImportSources() { }

    public static ConfigImportSource connectMBox(Context context, String address, String code) throws Exception {
        return LanSyncClient.connect(context, address, code);
    }
}
