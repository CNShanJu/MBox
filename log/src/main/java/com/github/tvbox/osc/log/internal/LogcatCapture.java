package com.github.tvbox.osc.log.internal;

import android.content.Context;
import android.util.Log;

import com.github.tvbox.osc.log.LogConfig;
import com.github.tvbox.osc.log.LogStore;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

/**
 * logcat 错误流捕获（internal：仅供 log 模块内部使用，勿被外部模块引用，只含本应用日志）：
 * `logcat --uid=<本应用uid>`(老系统退回 --pid=<本进程>) 只抓当前应用，
 * 绝不抓其他应用/系统日志；默认只保留 ERROR(本应用 E 级,即"错误日志")，
 * 日志级别调为 DEBUG 时才全量保留 V/D/I/W/E 便于深挖(显式逃生口)。
 * <p>
 * 按天写入 filesDir/app_logs/logcat-yyyy-MM-dd.log（与旧 AppLog 共用目录），
 * 单文件超 8MB 滚动分段、目录总大小上限 48MB 自动清理、保留天数跟随 {@link LogConfig#getRetentionDays()}。
 * <p>
 * 与业务日志（Room）互补：业务日志结构化可筛选("业务日志"Tab)，这里保留本应用错误流("错误日志"Tab)。
 * 开关由 {@link LogStore#setEnabled(boolean)} 联动（默认关）。
 * Context 由 {@link LogStore#init(Context)} 注入（独立模块，不依赖 app 类）。
 */
public final class LogcatCapture {

    /** 与旧 AppLog 共用目录（app_logs），前缀 logcat- 区分 */
    private static final String DIR = "app_logs";
    private static final String PREFIX = "logcat-";
    private static final String SUFFIX = ".log";
    private static final int BATCH_LINES = 100;
    private static final long FLUSH_MS = 1500;
    /**
     * 框架/系统"错误级"噪音特征(命中即不写入错误日志文件):
     * 这些不是应用的真实错误——资源 id 误探测(Invalid resource ID)、
     * hiddenapi 越权提示、ContentCapture/WebViewInfoPicker 视图探查、
     * SELinux avc 拒绝、渲染器/GC/输入法噪音等。真正的崩溃/业务异常
     * (FATAL/我们的异常栈)不含这些特征,仍会完整保留。
     * <p>
     * 注意:`logcat --uid=本应用uid` 抓的是"本应用进程"的全部 E 级日志,而厂商 ROM 的框架类
     * (联想 ZUI 的 OvfBaseDialog/ZuiHandWritingManager/CustomLongPressHelper、系统输入法 ImeBackDispatcher、
     * 图形栈 ashmem/BufferQueueProducer 等)是**在应用进程内加载**的,它们的 E 级提示(含自己 catch 后打的异常栈)
     * 同样会被抓到,但都不是应用代码的问题(已核对:仓库内无任何 setFrameRate/preferredRefreshRate 调用,
     * 也无 CustomLongPressHelper 类),一律按噪音丢弃。
     */
    private static final List<String> SYSTEM_NOISE_MARKERS = java.util.Arrays.asList(
            "Invalid resource ID ",
            "Unable to find resource ID #0x",
            "hiddenapi: Accessing hidden",
            "ContentCapture",
            "WebViewInfoPicker",
            "Access denied finding property",
            "type=1400 audit(",
            "Image decoding logging dropped!",
            "Compiler allocated ",
            "Waiting for a blocking GC",
            "WaitForGcToComplete blocked",
            "ImeTracker",
            "Multicast lock",
            "JIT profile",
            // 已知良性噪音(装饰性/系统内部提示,无排障价值):
            "RippleDrawable",            // 涟漪在非硬件加速画布上跳过动画(纯装饰)
            "Invalid resource ID",       // 第三方组件解析不存在的资源id(多为无副作用误用)
            "Seed missing signature",    // WebView variations seed 缺失(系统内部)
            "variations_seed_loader",    // 同上(chromium)
            "studio.deploy",             // Android Studio 部署清理提示
            "Unable to resolve path",    // jar/系统解析 base.apk 路径(更新后陈旧路径)
            "no such table: Config",     // 第三方 jar 查 CatVod 遗留 Config 表(已由预置空库兜底)
            "databases/tv",              // 第三方 jar 打开私有 tv 库(同上,防旧版本残留刷屏)
            // 厂商 ROM 框架类(在应用进程内加载,E 级提示与业务无关);
            // 这四条按「tag 位」匹配(`E/Tag(pid):`,本模块用 logcat -v time),不用裸类名——
            // 否则应用真崩溃时栈里恰好有一帧落在这些 ROM 类上,那一行会被误吞(丢了最关键的帧)。
            "E/OvfBaseDialog(",              // 联想 ZUI 系统弹窗框架(反复打 superDismiss unRegister…,纯时序提示)
            "E/ZuiHandWritingManager(",      // 联想 ZUI 手写输入框架构造提示(Constructor called)
            "E/ImeBackDispatcher(",          // 系统输入法回调注销不到(键盘收起时的常规提示)
            "E/CustomLongPressHelper(",      // ROM 长按助手对已回收 View 取 getContext() 的 NPE,
                                             // 由 ROM 自行 catch 后整段打栈(非应用崩溃,PID 不变,应用侧无法修)
            "Pinning is deprecated",         // 系统 ashmem 弃用提示(Android Q 起,系统内部行为;消息特征,无 tag)
            "BufferQueueProducer::",         // 图形栈 BufferQueue 告警(如 setFrameRate 兼容值越界,厂商显示适配发起)
            // 厂商媒体栈噪音(tag 用 AOSP 通用名,故按消息特征匹配——那 tag 下还有真实编解码错误要留):
            "Media Quality Service not found",              // 厂商画质服务不存在(本机没这服务,每次起播都打)
            "Failed to query component interface",          // 厂商编解码组件接口查询失败(tag=进程名,起播时成对出现)
            "E/HeifDecoderImpl(",                           // 厂商 HEIF 解码器不支持 getSize(探测不支持的格式,无害)
            "--------- beginning of "        // logcat 缓冲区分隔行(非日志内容,清理后更易读)
    );
    /**
     * 单个 logcat 文件大小上限(字节)。超过后滚动成 logcat-yyyy-MM-dd.1.log 等分段文件,
     * 避免某一天日志量爆炸时单个文件无限增长(曾导致应用占用存储激增)。
     */
    private static final long MAX_FILE_BYTES = 8L * 1024 * 1024;
    /** logcat 目录总大小上限(字节):所有分段/日期文件合计超限后删除最旧文件 */
    private static final long MAX_TOTAL_BYTES = 48L * 1024 * 1024;
    private static final long DAY_MS = 24L * 3600 * 1000;

    private static final Object LOCK = new Object();
    private static volatile Process process;
    private static volatile Thread thread;
    private static volatile Context appContext;

    private LogcatCapture() {
    }

    /** 由 LogStore.init 注入 application context（独立模块不依赖 app 类） */
    public static void setAppContext(Context context) {
        appContext = context == null ? null : context.getApplicationContext();
    }

    /** 文件目录计算用（internal 包内使用；导出 cacheDir 由 LogStore 自持 appContext） */
    static Context appContext() {
        return appContext;
    }

    /** 启动捕获（幂等）；未注入 context（模块未初始化/降级模式）时静默不启动 */
    public static void start() {
        if (appContext() == null) return;
        if (thread != null && thread.isAlive()) return;
        synchronized (LOCK) {
            if (thread != null && thread.isAlive()) return;
            try {
                File dir = logDir();
                if (!dir.exists()) dir.mkdirs();
                // 启动即先做一次清理:把上次遗留的超限/超期文件收掉,避免一开启就占满存储
                cleanupFiles();
                Process p;
                try {
                    // 只抓本应用:优先 logcat --uid=<本应用uid>(Android 8+)
                    p = Runtime.getRuntime().exec(buildCommand(true));
                } catch (Throwable th) {
                    p = null;
                }
                if (p == null) {
                    // uid 参数缺失等极端情况:退回 --pid=<本进程> 仍只含本应用
                    p = Runtime.getRuntime().exec(buildCommand(false));
                }
                final Process first = p;
                process = p;
                Thread t = new Thread(() -> readLoop(first), "tvbox-logcat");
                t.setDaemon(true);
                thread = t;
                t.start();
            } catch (Throwable th) {
                Log.e("LogcatCapture", "启动 logcat 捕获失败", th);
                process = null;
                thread = null;
            }
        }
    }

    /**
     * 组装 logcat 命令:
     * ① --uid=本应用uid(Android 8+);老系统不支持时退回 --pid=本进程pid——两者都只会拿到本应用日志,
     *    绝不用无过滤的全量 logcat(那会把整机日志写进应用目录导致存储激增)。
     * ② 记录级别:默认只保留 ERROR(本应用 E 级)——"错误日志"Tab 定位就是排障看错误;
     *    框架/库的大量 INFO/WARN 刷屏(AssetManager/Choreographer/MediaCodec/Adreno 等)不进文件;
     *    只有把日志级别调为 DEBUG(log_level=0)时才全量保留 V/D/I/W/E 便于深挖(显式逃生口)。
     *    业务上有意义的事件(搜索/播放/下载等)走 Room 结构化日志, 不依赖这里的原始流。
     */
    private static String[] buildCommand(boolean useUid) {
        List<String> cmd = new ArrayList<>();
        cmd.add("logcat");
        cmd.add("-v");
        cmd.add("time");
        if (useUid) {
            cmd.add("--uid=" + android.os.Process.myUid());
        } else {
            cmd.add("--pid=" + android.os.Process.myPid());
        }
        cmd.add("-T");
        cmd.add("1");
        int level = LogConfig.getLevel();
        if (level == LogStore.LEVEL_DEBUG) {
            // DEBUG: 全量(V/D/I/W/E), 深挖问题用
        } else {
            cmd.add("*:E"); // 默认:只记本应用 ERROR(错误日志)
        }
        return cmd.toArray(new String[0]);
    }

    /** 读取 logcat 输出循环;--uid 不被支持(老系统)导致进程立即退出时,用 --pid 重启一次继续读 */
    private static void readLoop(Process firstProc) {
        Process current = firstProc;
        boolean canPidFallback = true;
        try {
            while (current != null) {
                if (process != current) break; // 已停止
                BufferedReader reader = null;
                boolean eof = false;
                try {
                    reader = new BufferedReader(new InputStreamReader(current.getInputStream(), StandardCharsets.UTF_8));
                    List<String> batch = new ArrayList<>();
                    long lastFlush = 0;
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (process != current) break; // 已停止
                        if (isSystemNoise(line)) continue; // 过滤框架/系统噪音
                        batch.add(line);
                        long now = System.currentTimeMillis();
                        if (batch.size() >= BATCH_LINES || now - lastFlush > FLUSH_MS) {
                            appendLines(batch);
                            lastFlush = now;
                        }
                    }
                    appendLines(batch);
                    eof = true;
                } catch (Throwable th) {
                    // 某些设备无权限读取 logcat:回退为直接忽略(业务日志仍正常)
                    Log.d("LogcatCapture", "logcat 读取终止: " + th.getMessage());
                    eof = true;
                } finally {
                    if (reader != null) {
                        try {
                            reader.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
                if (!eof || !canPidFallback || process != current) {
                    break;
                }
                // 流结束且不是我们主动停止:大概率是 --uid 参数不被当前系统支持而立即退出,
                // 换 --pid 再抓一次(仍是本应用日志);仍未成功则放弃,不做全量抓取
                canPidFallback = false;
                boolean badExit = false;
                try {
                    if (!current.isAlive()) {
                        badExit = current.exitValue() != 0;
                    }
                } catch (Throwable ignored) {
                }
                if (!badExit) break;
                try {
                    Process pidProc = Runtime.getRuntime().exec(buildCommand(false));
                    if (pidProc != null && pidProc.isAlive()) {
                        process = pidProc;
                        current = pidProc;
                        continue;
                    }
                } catch (Throwable ignored) {
                }
                break;
            }
        } catch (Throwable th) {
            Log.d("LogcatCapture", "logcat 读取循环终止: " + th.getMessage());
        }
    }

    /** 停止捕获 */
    public static void stop() {
        Process p = process;
        process = null;
        thread = null;
        if (p != null) {
            try {
                p.destroy();
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------
    // 读侧（供 LogStore 门面暴露给日志页：错误日志 Tab 的文件级操作）
    // 只列/读/清/导本模块的 logcat-*.log(本应用 E 级错误流);
    // 旧 AppLog 的 app-*.log 是已退役写通道的残留,不混入"错误日志"展示。
    // ------------------------------------------------------------------

    /** 列出本模块 logcat 错误日志文件（名称倒序=新的在前）；未注入 context/无文件返回空表 */
    public static List<File> listLogFiles() {
        List<File> files = new ArrayList<>();
        try {
            if (appContext() == null) return files;
            File dir = logDir();
            File[] fs = dir.listFiles();
            if (fs == null) return files;
            for (File f : fs) {
                if (f.isFile() && f.getName().startsWith(PREFIX) && f.getName().endsWith(SUFFIX)) {
                    files.add(f);
                }
            }
            Collections.sort(files, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return b.getName().compareTo(a.getName());
                }
            });
        } catch (Throwable ignored) {
        }
        return files;
    }

    /**
     * 高效读取文件末尾最近 maxLines 行：顺序读 + 环形缓冲只保留尾部，
     * 避免一次性加载全文件(大 logcat 也能毫秒级返回)；文件不存在返回空表。
     */
    public static List<String> readTail(File file, int maxLines) {
        List<String> lines = new ArrayList<>();
        if (file == null || !file.exists()) return lines;
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
            LinkedList<String> ring = new LinkedList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                ring.add(line);
                if (ring.size() > maxLines) ring.removeFirst();
            }
            lines.addAll(ring);
        } catch (Throwable th) {
            Log.e("LogcatCapture", "读 logcat 文件失败", th);
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

    /** 清空全部 logcat 文件（分段 + 当日） */
    public static void clearAll() {
        synchronized (LOCK) {
            for (File f : listLogFiles()) {
                try {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 导出全部 logcat 文件到一个 txt（放 cacheDir，可 FileProvider 分享）。
     *
     * @return 导出文件；无文件/未注入 context 返回 null
     */
    public static File exportAll() {
        try {
            if (appContext() == null) return null;
            List<File> files = listLogFiles();
            if (files.isEmpty()) return null;
            File out = new File(appContext().getCacheDir(), "logcat_export_" + System.currentTimeMillis() + ".txt");
            FileWriter fw = new FileWriter(out, false);
            try {
                for (File f : files) {
                    fw.write("===== " + f.getName() + " =====\n");
                    for (String line : readTail(f, Integer.MAX_VALUE - 8)) {
                        fw.write(line + "\n");
                    }
                    fw.write("\n");
                }
            } finally {
                fw.close();
            }
            return out;
        } catch (Throwable th) {
            Log.e("LogcatCapture", "导出 logcat 文件失败", th);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 按天文件（写侧）
    // ------------------------------------------------------------------

    /** 框架/系统"错误级"噪音判定:命中任一特征即整行丢弃,不进错误日志文件 */
    private static boolean isSystemNoise(String line) {
        if (line == null) return true;
        for (String marker : SYSTEM_NOISE_MARKERS) {
            if (line.contains(marker)) return true;
        }
        return false;
    }

    private static void appendLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) return;
        synchronized (LOCK) {
            try {
                File dir = logDir();
                if (!dir.exists()) dir.mkdirs();
                File file = todayFile();
                StringBuilder sb = new StringBuilder(lines.size() * 96);
                for (String l : lines) sb.append(l).append('\n');
                FileOutputStream fos = new FileOutputStream(file, true);
                try {
                    fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                } finally {
                    try {
                        fos.close();
                    } catch (Throwable ignored) {
                    }
                }
                rotateIfNeeded(file);
                cleanupFiles();
            } catch (Throwable th) {
                Log.e("LogcatCapture", "写 logcat 文件失败", th);
            }
        }
    }

    private static File logDir() {
        return new File(appContext().getFilesDir(), DIR);
    }

    private static File todayFile() {
        return new File(logDir(), PREFIX + new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date()) + SUFFIX);
    }

    /** 当日文件超过单文件上限时滚动为 logcat-yyyy-MM-dd.N.log(与活跃文件同前缀,便于按天查看) */
    private static void rotateIfNeeded(File file) {
        if (file == null || !file.exists() || file.length() <= MAX_FILE_BYTES) return;
        try {
            String path = file.getAbsolutePath();
            String stem = path.substring(0, path.length() - SUFFIX.length());
            int i = 1;
            File seg;
            do {
                seg = new File(stem + "." + i + SUFFIX);
                i++;
            } while (seg.exists());
            if (!file.renameTo(seg)) {
                // 重命名失败(极少见)则忽略,下一次追加时再试
                return;
            }
        } catch (Throwable ignored) {
        }
    }

    /** 清理策略:① 删除超过 LogConfig 保留天数的文件;② 总大小超过上限时删除最旧的滚动文件(不删当前活跃文件) */
    private static void cleanupFiles() {
        try {
            File dir = logDir();
            File[] files = dir.listFiles();
            if (files == null) return;
            List<File> keep = new ArrayList<>();
            long now = System.currentTimeMillis();
            long retentionDays = Math.max(1, LogConfig.getRetentionDays());
            for (File f : files) {
                if (!f.isFile() || !f.getName().startsWith(PREFIX) || !f.getName().endsWith(SUFFIX)) {
                    continue;
                }
                if (now - f.lastModified() > retentionDays * DAY_MS) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                } else {
                    keep.add(f);
                }
            }
            if (keep.isEmpty()) return;
            // 按最后修改时间升序(最旧的在前),总大小仍超限时优先删除旧文件
            java.util.Collections.sort(keep, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            String activeName = todayFile().getName();
            long total = 0;
            for (File f : keep) total += f.length();
            for (File f : keep) {
                if (total <= MAX_TOTAL_BYTES) break;
                if (f.getName().equals(activeName)) continue; // 保留当前正在写的文件
                long len = f.length();
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                total -= len;
            }
        } catch (Throwable ignored) {
        }
    }
}
