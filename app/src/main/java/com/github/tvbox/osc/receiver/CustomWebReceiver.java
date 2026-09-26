package com.github.tvbox.osc.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.ArrayList;
import java.util.List;

/**
 * @author pj567
 * @date :2021/1/5
 * @description:
 */
public class CustomWebReceiver extends BroadcastReceiver {
    public static String action = "android.content.movie.custom.web.Action";

    public static String REFRESH_SOURCE = "source";
    public static String REFRESH_LIVE = "live";
    public static String REFRESH_PARSE = "parse";

    public static List<Callback> callback = new ArrayList<>();

    public interface Callback {
        void onChange(String action, Object obj);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        // intent/extras/action extra 缺失时直接返回,避免 NPE(exported=false 后仅本进程显式广播可达)
        if (intent == null || intent.getAction() == null) return;
        if (!action.equals(intent.getAction())) return;
        if (intent.getExtras() == null) return;
        String refreshAction = intent.getExtras().getString("action");
        if (refreshAction == null) return;
        if (refreshAction.equals(REFRESH_PARSE)) {
            /*String name = intent.getExtras().getString("name");
            String url = intent.getExtras().getString("url");*/
            return;
        } else if (refreshAction.equals(REFRESH_LIVE)) {
            return;
        } else {
            return;
        }
        /*if (callback != null) {
            for (Callback call : callback) {
                call.onChange(action, refreshObj);
            }
        }*/
    }
}