package com.github.tvbox.osc.util;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.github.tvbox.osc.log.Category;
import com.github.tvbox.osc.log.LogStore;
import com.github.tvbox.osc.spiderapi.CmsApiRules;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

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
                LogStore.log(Category.SUBSCRIPTION, "订阅: 阅读视频规则识别成功 " + spec.name
                        + " 分类=" + spec.routes.size() + " 列表路径=" + spec.listPath
                        + " 播放字段=" + spec.mediaPath + " 请求头=" + spec.headers.keySet());
                int categoryIndex = 0;
                for (Map.Entry<String, String> route : spec.routes.entrySet()) {
                    if (cancelled.get()) return;
                    if (categoryIndex++ >= 3) break;
                    String listUrl = spec.probeUrl(route.getValue());
                    report(progress, "正在验证分类接口 " + categoryIndex + "/3");
                    LogStore.log(Category.SUBSCRIPTION, "订阅: 阅读视频分类探测 " + spec.name
                            + " 地址=" + listUrl);
                    String response = HttpClient.getQuietly(listUrl, spec.headers);
                    if (cancelled.get()) return;
                    if (response == null || response.isEmpty()) {
                        reason = "分类接口无响应或返回 HTTP 错误";
                        LogStore.fail(Category.SUBSCRIPTION, "订阅: 阅读视频分类无响应/HTTP错误 " + listUrl);
                        continue;
                    }
                    List<JsonObject> items = spec.listItems(response);
                    LogStore.log(Category.SUBSCRIPTION, "订阅: 阅读视频分类响应 " + listUrl
                            + " 字符=" + response.length() + " 条目=" + items.size());
                    if (items.isEmpty()) {
                        reason = "分类响应不符合书源的 ruleArticles";
                        LogStore.fail(Category.SUBSCRIPTION, "订阅: 阅读视频分类数据未命中 " + listUrl
                                + " 响应=" + responseSummary(response));
                        continue;
                    }
                    reason = "详情接口没有取得播放地址";
                    for (int i = 0; i < Math.min(3, items.size()); i++) {
                        if (cancelled.get()) return;
                        String detailUrl = spec.detailUrl(items.get(i));
                        if (detailUrl == null) continue;
                        report(progress, "正在验证详情与播放地址");
                        LogStore.log(Category.SUBSCRIPTION, "订阅: 阅读视频详情探测 " + detailUrl);
                        String detail = HttpClient.getQuietly(detailUrl, spec.headers);
                        if (cancelled.get()) return;
                        boolean media = detail != null && spec.hasMedia(detail);
                        LogStore.log(Category.SUBSCRIPTION, "订阅: 阅读视频详情响应 " + detailUrl
                                + " 字符=" + (detail == null ? 0 : detail.length()) + " 播放字段=" + media);
                        if (!media) {
                            LogStore.fail(Category.SUBSCRIPTION, "订阅: 阅读视频详情缺少播放字段 " + detailUrl
                                    + " 响应=" + responseSummary(detail));
                        }
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
            } catch (Throwable error) {
                reason = "阅读视频源探测异常";
                LogStore.fail(Category.SUBSCRIPTION, "订阅: 阅读视频源探测异常 " + spec.name + " " + error);
                Log.e("SubscriptionImport", "阅读视频源探测异常: " + spec.host, error);
            }
            File file = result;
            String failedReason = reason;
            if (file == null) {
                LogStore.fail(Category.SUBSCRIPTION, "订阅: 阅读视频源未接入 " + spec.name
                        + " 原因=" + failedReason + " 站点=" + spec.host);
                Log.e("SubscriptionImport", "阅读视频源未接入: " + spec.host + " " + failedReason);
            } else {
                LogStore.success(Category.SUBSCRIPTION, "订阅: 阅读视频源探测通过 " + spec.name
                        + " 分类=" + spec.routes.size() + " 配置=" + file.getName());
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

    private static String responseSummary(String response) {
        if (response == null || response.isEmpty()) return "空响应";
        try {
            JsonElement parsed = JsonParser.parseString(response);
            if (parsed.isJsonObject()) {
                JsonObject obj = parsed.getAsJsonObject();
                String code = obj.has("code") ? obj.get("code").toString() : "无";
                String message = obj.has("msg") ? obj.get("msg").toString() : "无";
                return "JSON code=" + code + " msg=" + message.substring(0, Math.min(80, message.length()))
                        + " 字段=" + obj.keySet();
            }
        } catch (RuntimeException ignored) { }
        String compact = response.replaceAll("\\s+", " ");
        return "非JSON " + compact.substring(0, Math.min(80, compact.length()));
    }

    private static File write(File outDir, String key, String json) {
        File file = new File(outDir, key + ".json");
        try {
            if (!outDir.exists() && !outDir.mkdirs()) return null;
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(file), StandardCharsets.UTF_8)) {
                writer.write(json);
            }
            return file;
        } catch (Exception error) {
            LogStore.fail(Category.SUBSCRIPTION, "订阅: 阅读视频源配置写入失败 " + error);
            Log.e("SubscriptionImport", "阅读视频源配置写入失败", error);
            return null;
        }
    }
}
