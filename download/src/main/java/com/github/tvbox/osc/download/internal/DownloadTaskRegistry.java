package com.github.tvbox.osc.download.internal;

import com.github.tvbox.osc.bean.DownloadTask;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 下载任务工厂注册表（4.6 任务对象化）：按资源特征(Feature)选择任务对象子类。
 * <p>
 * 新增下载类型 = 注册 特征判定 + 工厂，调度器零改动：
 * <pre>
 *   DownloadTaskRegistry.register(
 *           t -&gt; 新类型特征,
 *           (t, l, executor) -&gt; new XxxDownloadTask(t, l, executor));
 * </pre>
 * 注册表有兜底（直链），{@link #create} 永不返回 null。
 */
public final class DownloadTaskRegistry {

    /** 特征判定: 命中则用该工厂创建任务对象 */
    public interface Feature {
        boolean matches(DownloadTask t);
    }

    /** 工厂: 创建任务对象(executor 为框架侧执行器,由调度器注入) */
    public interface Factory {
        BaseDownloadTask create(DownloadTask t, TaskListener listener, DownloadExecutor executor);
    }

    private static final class Entry {
        final Feature feature;
        final Factory factory;

        Entry(Feature feature, Factory factory) {
            this.feature = feature;
            this.factory = factory;
        }
    }

    private static final List<Entry> ENTRIES = new CopyOnWriteArrayList<>();

    static {
        // ⚠ register 是 add(0,...) 后注册先匹配: 必须先注册"兜底直链", 再注册"HLS"——
        // 若顺序颠倒, t->true 兜底会永远排在最前, 所有任务(含 m3u8)都被分发成直链,
        // 导致 m3u8 被当直链下载(下出播放列表/几KB假文件/魔数校验失败)。这是历史根因。
        register(t -> true, NormalFileDownloadTask::new);
        register(t -> t.url != null && t.url.toLowerCase().contains(".m3u8"), M3u8DownloadTask::new);
    }

    private DownloadTaskRegistry() {
    }

    /** 注册新下载类型(后注册先匹配; 兜底直链永远最后,保证 create 非空) */
    public static void register(Feature feature, Factory factory) {
        ENTRIES.add(0, new Entry(feature, factory));
    }

    /** 按特征创建任务对象; 无匹配时回退直链(永不返回 null) */
    public static BaseDownloadTask create(DownloadTask t, TaskListener listener, DownloadExecutor executor) {
        for (Entry e : ENTRIES) {
            if (e.feature.matches(t)) {
                BaseDownloadTask obj = e.factory.create(t, listener, executor);
                String typeName = obj.getClass().getSimpleName();
                android.util.Log.i("TVBox-Download", "任务分发: " + (t == null || t.fileName == null ? "?" : t.fileName)
                        + " -> " + typeName + " url=" + (t == null ? "null" : t.url));
                com.github.tvbox.osc.download.internal.DownloadLog.LOG.info(
                        com.github.tvbox.osc.download.DownloadSubType.RESOLVE,
                        "任务分发: " + (t == null || t.fileName == null ? "?" : t.fileName) + " -> " + typeName
                                + " url=" + (t == null ? "null" : t.url),
                        com.github.tvbox.osc.download.internal.DownloadLog.extras(t == null ? null : t.episodeId));
                return obj;
            }
        }
        android.util.Log.i("TVBox-Download", "任务分发(兜底直链): " + (t == null || t.fileName == null ? "?" : t.fileName)
                + " url=" + (t == null ? "null" : t.url));
        com.github.tvbox.osc.download.internal.DownloadLog.LOG.info(
                com.github.tvbox.osc.download.DownloadSubType.RESOLVE,
                "任务分发(兜底直链): " + (t == null || t.fileName == null ? "?" : t.fileName)
                        + " url=" + (t == null ? "null" : t.url),
                com.github.tvbox.osc.download.internal.DownloadLog.extras(t == null ? null : t.episodeId));
        return new NormalFileDownloadTask(t, listener, executor);
    }
}
