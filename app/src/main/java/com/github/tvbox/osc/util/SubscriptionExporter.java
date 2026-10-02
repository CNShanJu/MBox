package com.github.tvbox.osc.util;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.Subscription;
import com.github.tvbox.osc.log.Category;
import com.github.tvbox.osc.log.LogStore;
import com.github.tvbox.osc.transfer.ConfigBundle;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 订阅导出:单条生成 JSON 描述,多条生成配置 ZIP；不做抓取或合并。
 *
 * <p>每条形如:
 * <pre>
 * {"name":"名称","type":"cms","origin":"direct","url":"http://…/api.php/provide/vod/"}
 * {"name":"名称","type":"clan","origin":"local","url":"clan://localhost/Android/data/…/x.json",
 *  "file":"x.json","content":"{原始文件内容}"}
 * </pre>
 * <ul>
 *   <li><b>type</b> = 源类型,由地址形态派生:http(s) → {@code cms}(远端接口/配置);
 *       {@code clan://} → {@code clan}(本地文件,内容随导出内嵌,换机/原文件删了也能还原)。</li>
 *   <li><b>origin</b> = 当初的添加方式({@code direct}/{@code local}/{@code json}),
 *       导入时据此重放"添加订阅"的对应步骤(见 SubscriptionActivity 的清单导入)。</li>
 * </ul>
 * <p>为什么不再"抓下来合并成一份配置":那样导出的是一份**合并后的配置**,导入回去只剩 1 条订阅,
 * 名称/来源/添加方式全丢;描述清单才能原样往返(远端配置本来就由 App 运行时按 url 自行拉取)。
 *
 * <p>约束:读本地文件在 {@link HeavyTaskUtil} 共享执行器上跑,结果切回主线程;
 * 同一时刻只认最后一次导出(epoch 自检),{@link #cancel()} 或新任务都会让旧任务的结果作废;
 * clan:// 路径先规范化并校验不逃出主存储根(防目录穿越),单份配置有大小上限。
 */
public final class SubscriptionExporter {

    /** 本地文件内容内嵌上限:正常配置也就几百 KB,超过多半是选错了文件 */
    private static final long MAX_CONFIG_BYTES = 8L * 1024 * 1024;
    /** 导出目录里最多留几个文件(缓存目录也做有界清理,避免越积越多) */
    private static final int KEEP_EXPORT_FILES = 4;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 导出任务代号:新任务/取消都会把旧任务的回调判为过期 */
    private static final AtomicLong RUN_EPOCH = new AtomicLong();

    public interface Callback {
        /** @param count 清单条数 */
        void onDone(File file, int count);

        void onError(String message);
    }

    private SubscriptionExporter() {
    }

    /** 取消正在跑的导出:在跑的循环会在下一条前退出,回来的结果一律作废 */
    public static void cancel() {
        RUN_EPOCH.incrementAndGet();
    }

    /**
     * 后台组装清单 + 落盘,主线程回调。
     *
     * @param items 勾选的订阅(顺序即清单顺序)
     */
    public static void export(final Context context, final List<Subscription> items, final Callback cb) {
        final Context app = context == null ? null : context.getApplicationContext();
        if (app == null || items == null || items.isEmpty()) {
            if (cb != null) cb.onError("请先选择要导出的订阅");
            return;
        }
        final long epoch = RUN_EPOCH.incrementAndGet();
        HeavyTaskUtil.getBigTaskExecutorService().execute(() -> {
            File out = null;
            String err = null;
            int count = 0;
            try {
                JsonArray arr = new JsonArray();
                for (Subscription item : items) {
                    if (epoch != RUN_EPOCH.get()) return;   // 已被取消/被新任务取代,别再读文件了
                    arr.add(describeForTransfer(item));
                }
                if (arr.size() == 0) {
                    err = "请先选择要导出的订阅";
                } else {
                    if (arr.size() > 1) {
                        File temporary = new ConfigBundle(app).exportSubscriptionSelection(arr);
                        try { out = writeArchive(app, temporary); }
                        finally { temporary.delete(); }
                    } else {
                        JsonObject single = new JsonObject();
                        single.addProperty("schema", 1);
                        single.addProperty("category", "subscriptions");
                        single.addProperty("createdAt", System.currentTimeMillis());
                        single.add("items", arr);
                        out = write(app, single.toString());
                    }
                    count = arr.size();
                }
            } catch (Throwable t) {
                t.printStackTrace();
                LogStore.fail(Category.SUBSCRIPTION, "订阅: 导出异常 " + t);
                err = "导出失败:" + t.getMessage();
            }
            final File fOut = out;
            final String fErr = err;
            final int fCount = count;
            if (epoch != RUN_EPOCH.get()) {
                if (out != null) out.delete();
                return;
            }
            MAIN.post(() -> {
                if (cb == null) return;
                if (fOut == null) cb.onError(fErr == null ? "导出失败" : fErr);
                else cb.onDone(fOut, fCount);
            });
        });
    }

    // ------------------------------------------------------------------
    // 描述清单
    // ------------------------------------------------------------------

    /** 一条订阅 → 描述对象(纯本地信息;type 派生、origin 取记录、本地文件内嵌内容) */
    /** 局域网同步复用同一份订阅描述清单，保留本地订阅的内嵌内容。 */
    public static JsonObject describeForTransfer(Subscription item) {
        JsonObject o = new JsonObject();
        String url = item.getUrl() == null ? "" : item.getUrl().trim();
        boolean local = url.startsWith("clan://");
        o.addProperty("name", item.getName());
        o.addProperty("type", local ? "clan" : "cms");
        o.addProperty("origin", item.getOrigin());
        o.addProperty("url", url);
        if (local) {
            File f = resolveClanFile(url);
            if (f == null || !f.isFile() || f.length() > MAX_CONFIG_BYTES)
                throw new IllegalStateException("本地订阅文件缺失或超过 8 MB：" + item.getName());
            o.addProperty("file", f.getName());
            try {
                String content = readText(f);
                if (content.trim().isEmpty()) throw new IOException("本地订阅内容为空");
                o.addProperty("content", content);
            } catch (IOException error) {
                throw new IllegalStateException("本地订阅文件读取失败：" + item.getName(), error);
            }
        }
        return o;
    }

    /** clan://localhost/<主存储内路径> → 主存储根下的真实文件;越界/非法返回 null */
    private static File resolveClanFile(String clanUrl) {
        String prefix = "clan://localhost/";
        if (!clanUrl.startsWith(prefix)) return null;
        String path = clanUrl.substring(prefix.length());
        try {
            if (path.contains("..")) return null;
            File root = Environment.getExternalStorageDirectory().getCanonicalFile();
            File f = new File(root, path).getCanonicalFile();
            if (f.equals(root) || !f.getPath().startsWith(root.getPath() + File.separator)) return null;
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String readText(File file) throws IOException {
        if (file.length() > MAX_CONFIG_BYTES) throw new IOException("配置过大(" + file.length() + "B)");
        StringBuilder sb = new StringBuilder((int) Math.min(file.length(), 1 << 16));
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            char[] buf = new char[8192];
            int n;
            while ((n = reader.read(buf)) > 0) {
                sb.append(buf, 0, n);
                if (sb.length() > MAX_CONFIG_BYTES) throw new IOException("配置过大");
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 落盘
    // ------------------------------------------------------------------

    /** 写入应用外部缓存目录(FileProvider 已覆盖 external-cache-path,可直接分享);保留最近几份 */
    private static File write(Context ctx, String json) throws IOException {
        File root = ctx.getExternalCacheDir();
        if (root == null) throw new IOException("导出目录不可用");
        File dir = new File(root, "subscription_export");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("导出目录不可用");
        prune(dir);
        File out = newExportFile(ctx, dir, ".json");
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(out), "UTF-8")) {
            writer.write(json);
        } catch (IOException error) {
            out.delete();
            throw error;
        }
        return out;
    }

    /** Copy the shared bulk package to FileProvider's external cache, then remove its private staging ZIP. */
    private static File writeArchive(Context ctx, File archive) throws IOException {
        File root = ctx.getExternalCacheDir();
        if (root == null) throw new IOException("导出目录不可用");
        File dir = new File(root, "subscription_export");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("导出目录不可用");
        prune(dir);
        File out = newExportFile(ctx, dir, ".zip");
        try (InputStream in = new FileInputStream(archive); OutputStream target = new FileOutputStream(out)) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) > 0) target.write(buffer, 0, n);
            return out;
        } catch (IOException error) {
            out.delete();
            throw error;
        }
    }

    private static File newExportFile(Context ctx, File dir, String suffix) throws IOException {
        String prefix = ctx.getString(R.string.app_name) + "订阅清单_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + "_";
        return File.createTempFile(prefix, suffix, dir);
    }

    /** 只留最近 {@link #KEEP_EXPORT_FILES} 份导出文件 */
    private static void prune(File dir) {
        try {
            File[] files = dir.listFiles();
            if (files == null || files.length < KEEP_EXPORT_FILES) return;
            Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
            for (int i = KEEP_EXPORT_FILES - 1; i < files.length; i++) {
                //noinspection ResultOfMethodCallIgnored
                files[i].delete();
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // 给导入侧复用:解析清单文本
    // ------------------------------------------------------------------

    /** 清单一律用 Gson 解析(与 SubscriptionActivity 的导入共用同一套容忍度),解析失败返回 null */
    public static JsonArray parseManifest(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.isEmpty()) return null;
        try {
            com.google.gson.JsonElement root = JsonParser.parseString(t);
            if (root.isJsonArray()) return root.getAsJsonArray();
            if (root.isJsonObject()) {
                com.google.gson.JsonElement items = root.getAsJsonObject().get("items");
                if (items != null && items.isJsonArray()) return items.getAsJsonArray();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
