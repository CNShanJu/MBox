package com.github.tvbox.osc.log.internal;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 日志导出文件的轮转(internal：仅供 log 模块内部使用，勿被外部模块引用)。
 * <p>
 * 业务日志与错误日志的"导出"都写 cacheDir，文件名带毫秒时间戳 —— 每点一次导出就新增一个文件
 * (业务日志那份最多 2000 行，错误日志那份是把全部 logcat 文件拼起来、可能几十 MB)，
 * 谁都不删就是只增不减。这里在写完新文件后按前缀只保留最近 {@link #KEEP} 个，旧的直接删掉。
 * <p>
 * 只碰 cacheDir 里本模块自己的导出文件：cacheDir 本身会被系统在存储紧张时回收，
 * 但应用不该把"系统迟早会清"当作自己的清理策略。
 */
public final class ExportFiles {

    /** 同类导出文件保留个数(含刚写出来的那个) */
    private static final int KEEP = 3;
    private static final String SUFFIX = ".txt";

    private ExportFiles() {
    }

    /**
     * 只保留最近 {@link #KEEP} 个 {@code prefix*.txt}，其余删除。
     * 按文件名倒序即"新在前"(名字里的 {@code System.currentTimeMillis()} 定长，字典序与时间序一致)。
     */
    public static void keepNewest(File dir, String prefix) {
        try {
            if (dir == null || prefix == null) return;
            File[] fs = dir.listFiles();
            if (fs == null) return;
            List<File> hits = new ArrayList<>();
            for (File f : fs) {
                if (f.isFile() && f.getName().startsWith(prefix) && f.getName().endsWith(SUFFIX)) {
                    hits.add(f);
                }
            }
            if (hits.size() <= KEEP) return;
            Collections.sort(hits, (a, b) -> b.getName().compareTo(a.getName()));
            for (int i = KEEP; i < hits.size(); i++) {
                //noinspection ResultOfMethodCallIgnored
                hits.get(i).delete();
            }
        } catch (Throwable ignored) {
        }
    }
}
