package com.github.tvbox.osc.util;

import android.os.Handler;
import android.os.Looper;

import com.github.tvbox.osc.log.Category;
import com.github.tvbox.osc.log.LogStore;
import com.github.tvbox.osc.spiderapi.CmsApiRules;
import com.github.tvbox.osc.transfer.SubscriptionImportFiles;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** 把可静态描述的阅读视频书源实探后转成单源配置。 */
public final class LegadoVideoImporter {
    public static final String RUNTIME_API = "assets://js/lib/legado_video.js";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Callback {
        void onFound(String name, File configFile);
        void onNotFound(String reason);
    }

    public interface Progress {
        void onStep(String hint);
    }

    private LegadoVideoImporter() { }

    public static void probe(LegadoVideoRules.Spec spec, File outDir, AtomicBoolean cancelled,
                             Callback callback, Progress progress) {
        if (callback == null) return;
        HeavyTaskUtil.getBigTaskExecutorService().execute(() -> {
            if (cancelled.get()) return;
            File result = null;
            String reason = "接口未返回可用列表";
            try {
                int categoryIndex = 0;
                for (Map.Entry<String, String> route : spec.routes.entrySet()) {
                    if (cancelled.get()) return;
                    if (categoryIndex++ >= 3) break;
                    String listUrl = spec.probeUrl(route.getValue());
                    report(progress, "正在验证分类接口 " + categoryIndex + "/3");
                    String response = HttpClient.getQuietly(listUrl, spec.headers);
                    if (cancelled.get()) return;
                    if (response == null || response.isEmpty()) {
                        reason = "分类接口无响应或返回 HTTP 错误";
                        continue;
                    }
                    List<JsonObject> items = spec.listItems(response);
                    if (items.isEmpty()) {
                        reason = "分类响应不符合书源的 ruleArticles";
                        continue;
                    }
                    reason = "详情接口没有取得播放地址";
                    for (int i = 0; i < Math.min(3, items.size()); i++) {
                        if (cancelled.get()) return;
                        String detailUrl = spec.detailUrl(items.get(i));
                        if (detailUrl == null) continue;
                        report(progress, "正在验证详情与播放地址");
                        String detail = HttpClient.getQuietly(detailUrl, spec.headers);
                        if (cancelled.get()) return;
                        boolean media = detail != null && spec.hasMedia(detail);
                        if (!media) continue;
                        String config = CmsApiRules.buildSubscriptionJson(spec.key, spec.name, 3,
                                RUNTIME_API, spec.extJson, spec.extJson.contains("\"searchRoute\"") ? 1 : 0,
                                1, 1);
                        if (cancelled.get()) return;
                        result = write(outDir, spec.key, config);
                        if (result == null) reason = "生成配置文件失败";
                        break;
                    }
                    if (result != null) break;
                }
            } catch (Throwable ignored) {
                reason = "阅读视频源探测异常";
            }
            File file = result;
            String failedReason = reason;
            if (file == null) {
                LogStore.fail(Category.SUBSCRIPTION, "订阅: 阅读视频源未接入 原因=" + failedReason);
            } else {
                LogStore.success(Category.SUBSCRIPTION, "订阅: 阅读视频源接入成功");
            }
            MAIN.post(() -> {
                if (file == null) callback.onNotFound(failedReason);
                else callback.onFound(spec.name, file);
            });
        });
    }

    private static void report(Progress progress, String hint) {
        if (progress != null) MAIN.post(() -> progress.onStep(hint));
    }

    private static File write(File outDir, String key, String json) {
        File file = new File(outDir, key + ".json");
        try {
            return SubscriptionImportFiles.runLocked(() -> {
                if (!outDir.exists() && !outDir.mkdirs()) return null;
                try (OutputStreamWriter writer = new OutputStreamWriter(
                        new FileOutputStream(file), StandardCharsets.UTF_8)) {
                    writer.write(json);
                }
                return file;
            });
        } catch (Exception ignored) {
            return null;
        }
    }
}
