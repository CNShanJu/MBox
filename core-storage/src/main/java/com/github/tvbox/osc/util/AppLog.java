package com.github.tvbox.osc.util;

import android.content.Context;
import com.github.tvbox.osc.config.HawkConfig;
import com.github.tvbox.osc.config.PrefsDataStore;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

/**
 * App 运行日志
 * <p>
 * 受 {@link HawkConfig#APP_LOG} 开关控制,默认关闭:
 * 仅当设置里开启"运行日志"后才写入文件,避免常驻记录耗费性能与存储。
 * 按天记录到 filesDir/app_logs/app-yyyy-MM-dd.log,单日保留最近 {@link #MAX_LINES_PER_DAY} 行。
 * 提供:写入(log)、logcat 完整捕获(startLogcatCapture/stopLogcatCapture)、
 * 按天查看(listLogFiles/readLines)、清空(clearAll)、导出(exportAll)。
 */
public class AppLog {

    private static final String LOG_DIR = "app_logs";
    private static final String FILE_PREFIX = "app-";
    private static final String FILE_SUFFIX = ".log";
    private static final int MAX_LINES_PER_DAY = 20000;

    private static final Object LOCK = new Object();

    /** logcat 捕获进程与线程(开关开启时持续写入完整运行日志) */
    private static volatile Process logcatProcess;
    private static volatile Thread logcatThread;

    /** 注入的 application context（独立模块，不依赖 app 类） */
    private static volatile Context appContext;

    /** App 启动时注入 context（AppLog 按天文件/导出用） */
    public static void setAppContext(Context context) {
        appContext = context == null ? null : context.getApplicationContext();
    }

    private AppLog() {
    }

    /** 追加一条日志(线程安全);开关关闭时直接忽略 */
    public static void log(String tag, String msg) {
        if (!PrefsDataStore.getBoolean(HawkConfig.APP_LOG, false)) return;
        String line = "[" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()) + "] " + tag + ": " + msg;
        List<String> lines = new ArrayList<>(1);
        lines.add(line);
        appendLines(lines);
    }

    /**
     * 启动 logcat 完整捕获:持续读取本应用(Uid)的所有系统日志,像 IDEA Logcat 一样写入日志文件。
     * 幂等,重复调用无副作用。
     */
    public static void startLogcatCapture() {
        if (logcatThread != null && logcatThread.isAlive()) return;
        synchronized (LOCK) {
            if (logcatThread != null && logcatThread.isAlive()) return;
            try {
                // -T 1:从最后一条开始跟随(不 dump 历史);-v time:带时间戳
                final Process p = Runtime.getRuntime().exec(new String[]{"logcat", "-v", "time", "-T", "1"});
                logcatProcess = p;
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        BufferedReader reader = null;
                        try {
                            reader = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
                            List<String> batch = new ArrayList<>();
                            long lastFlush = 0;
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (logcatProcess != p) break; // 已停止
                                batch.add(line);
                                long now = System.currentTimeMillis();
                                if (batch.size() >= 100 || now - lastFlush > 1500) {
                                    appendLines(batch);
                                    lastFlush = now;
                                }
                            }
                            appendLines(batch);
                        } catch (Throwable ignored) {
                        } finally {
                            if (reader != null) {
                                try {
                                    reader.close();
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                    }
                }, "applog-logcat");
                t.setDaemon(true);
                logcatThread = t;
                t.start();
            } catch (Throwable th) {
                th.printStackTrace();
                logcatProcess = null;
                logcatThread = null;
            }
        }
    }

    /** 停止 logcat 捕获(开关关闭时调用) */
    public static void stopLogcatCapture() {
        Process p = logcatProcess;
        logcatProcess = null;
        logcatThread = null;
        if (p != null) {
            try {
                p.destroy();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 批量写入日志文件(线程安全);开关关闭时忽略 */
    private static void appendLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) return;
        if (!PrefsDataStore.getBoolean(HawkConfig.APP_LOG, false)) return;
        synchronized (LOCK) {
            try {
                File dir = logDir();
                if (!dir.exists()) dir.mkdirs();
                File file = todayFile();
                StringBuilder sb = new StringBuilder(lines.size() * 64);
                for (String l : lines) sb.append(l).append('\n');
                FileOutputStream fos = new FileOutputStream(file, true);
                fos.write(sb.toString().getBytes("UTF-8"));
                fos.flush();
                fos.close();
                trimFile(file);
            } catch (Throwable th) {
                th.printStackTrace();
            }
        }
    }

    private static File todayFile() {
        return new File(logDir(), FILE_PREFIX + new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date()) + FILE_SUFFIX);
    }

    private static void trimFile(File file) {
        try {
            List<String> lines = readLines(file);
            if (lines.size() > MAX_LINES_PER_DAY) {
                List<String> tail = lines.subList(lines.size() - MAX_LINES_PER_DAY, lines.size());
                FileWriter fw = new FileWriter(file, false);
                for (String l : tail) fw.write(l + "\n");
                fw.close();
            }
        } catch (Throwable ignored) {
        }
    }

    private static File logDir() {
        return new File(appContext.getFilesDir().getAbsolutePath() + "/" + LOG_DIR);
    }

    /** 按天列出日志文件(新的在前) */
    public static List<File> listLogFiles() {
        List<File> files = new ArrayList<>();
        File dir = logDir();
        if (dir.exists()) {
            File[] fs = dir.listFiles();
            if (fs != null) {
                for (File f : fs) {
                    if (f.isFile() && f.getName().endsWith(FILE_SUFFIX)) files.add(f);
                }
            }
        }
        Collections.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return b.getName().compareTo(a.getName());
            }
        });
        return files;
    }

    /** 读取日志文件全部行 */
    public static List<String> readLines(File file) {
        List<String> lines = new ArrayList<>();
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) lines.add(line);
            reader.close();
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return lines;
    }

    /**
     * 高效读取日志文件末尾的最近 maxLines 行:顺序读取 + 环形缓冲只保留尾部,
     * 避免一次性加载全文件(几万行 logcat 也能毫秒级返回)。
     */
    public static List<String> readTail(File file, int maxLines) {
        List<String> lines = new ArrayList<>();
        if (file == null || !file.exists()) return lines;
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            LinkedList<String> ring = new LinkedList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                ring.add(line);
                if (ring.size() > maxLines) ring.removeFirst();
            }
            lines.addAll(ring);
        } catch (Throwable th) {
            th.printStackTrace();
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return lines;
    }

    /** 清空全部日志 */
    public static void clearAll() {
        synchronized (LOCK) {
            for (File f : listLogFiles()) f.delete();
        }
    }

    /**
     * 导出全部日志到一个 txt 文件(放 cacheDir,可通过 FileProvider 分享)
     *
     * @return 导出文件;无日志返回 null
     */
    public static File exportAll() {
        try {
            List<File> files = listLogFiles();
            if (files.isEmpty()) return null;
            File out = new File(appContext.getCacheDir().getAbsolutePath() + "/app_log_export.txt");
            FileWriter fw = new FileWriter(out, false);
            for (File f : files) {
                fw.write("===== " + f.getName() + " =====\n");
                for (String line : readLines(f)) fw.write(line + "\n");
                fw.write("\n");
            }
            fw.close();
            return out;
        } catch (Throwable th) {
            th.printStackTrace();
            return null;
        }
    }
}
