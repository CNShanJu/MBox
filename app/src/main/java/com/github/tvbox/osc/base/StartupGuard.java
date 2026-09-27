package com.github.tvbox.osc.base;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * 启动期防护:错误页进程最小化 + 启动崩溃循环熔断(安全模式)。
 *
 * <p><b>为什么需要它</b>:崩溃页(CustomActivityOnCrash)自带的熔断只看"距上次崩溃是否小于
 * {@code minTimeBetweenCrashesMs}";用户点"重新启动"到新进程走到这里必然超过该窗口,
 * 于是熔断永不命中。一旦有"启动路径上的坏数据"(最典型:损坏的下载任务文件),每次启动都崩在
 * 同一条路径上,只能卸载重装。这里改用<b>连续未健康启动次数</b>计数:
 * 同一个 5 分钟窗口内连续 {@link #SAFE_MODE_AT} 次都没能撑到健康门槛 → 本次启动进
 * {@linkplain #isSafeMode() 安全模式},跳过最容易"开机即崩"的初始化,先把 App 拉起来让用户自救;
 * 任何一次健康启动(撑过 {@link #HEALTHY_AFTER_MS})即清零,下次恢复正常路径。
 *
 * <p><b>为什么这里直接用 SharedPreferences</b>:崩溃路径必须同步落盘({@code commit()}),
 * 而 core-storage 的 {@code PrefsDataStore}(DataStore)是异步的、且不适合在进程濒死时使用;
 * 本类位于 app 启动基座层而非 UI 层,读写的也只是崩溃计数这一进程级状态。
 */
public final class StartupGuard {

    private static final String TAG = "StartupGuard";

    /** 崩溃页所在进程(第三方库 manifest 里 {@code android:process=":error_activity"}) */
    private static final String ERROR_PROCESS_SUFFIX = ":error_activity";

    private static final String PREF_FILE = "startup_guard";
    private static final String KEY_ATTEMPTS = "boot_attempts";
    /**
     * 计数口径版本:1 = 按"启动次数"(老口径,会把快速重启误判成失败);2 = 只数**真实崩溃**。
     * 升级到 2 时清一次老计数,免得用户卡在老口径判出来的安全模式里出不来。
     */
    private static final int SCHEMA = 2;
    private static final String KEY_SCHEMA = "guard_schema";
    private static final String KEY_LAST_AT = "last_boot_at";
    /** 安全模式冷却期截止时间戳(0=未在冷却期) */
    private static final String KEY_SAFE_UNTIL = "safe_mode_until";

    /** 计数窗口:超过这个间隔的两次启动不算"连续失败"(正常次日启动不会被计入) */
    private static final long WINDOW_MS = 5 * 60_000L;
    /** 窗口内连续失败达到该次数即进安全模式 */
    private static final int SAFE_MODE_AT = 3;
    /** 启动后撑过这段时间即视为"本次启动健康"(启动即崩的场景都在此之前) */
    private static final long HEALTHY_AFTER_MS = 20_000L;
    /**
     * 进入安全模式后的最短保持时长。
     * <p>
     * 没有它就会"抖":三次崩溃换来一次安全模式启动 → 这次启动健康(JS 引擎被跳过,自然不崩)→
     * 计数清零 → 下次又走正常路径继续崩,用户每 4 次启动里只有 1 次能用。保持一段稳定的可用期,
     * 用户才有时间进设置把出问题的那一项处理掉;健康启动之后计数清零,冷却期一过即恢复正常。
     */
    private static final long SAFE_MODE_COOLDOWN_MS = 10 * 60_000L;

    /** 错误页进程自身崩溃的留痕文件(放在 logcat 目录下,但名字不是 logcat-* 故不会被日志页当原始日志列出) */
    private static final String ERROR_PROCESS_TRACE = "app_logs/error_process_crash.log";
    /** 留痕文件上限:只保留最近一段,避免反复崩溃把文件撑大 */
    private static final long ERROR_TRACE_MAX = 64L * 1024L;

    private static volatile boolean safeMode = false;
    private static volatile int bootAttempts = 0;

    private StartupGuard() {
    }

    // ------------------------------------------------------------------
    // 错误页进程
    // ------------------------------------------------------------------

    /** 当前是否运行在崩溃错误页进程(:error_activity) */
    public static boolean isErrorActivityProcess() {
        String name = currentProcessName();
        return name != null && name.endsWith(ERROR_PROCESS_SUFFIX);
    }

    /**
     * 进程名读 {@code /proc/self/cmdline}(与 CustomActivityOnCrash 自身的判断同源);
     * 读不到就按"非错误页进程"处理,不影响主进程启动。
     */
    private static String currentProcessName() {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader("/proc/self/cmdline"));
            String line = reader.readLine();
            // cmdline 以 '\0' 结尾/分隔;String.trim() 会去掉 <= U+0020 的字符(含 NUL)
            return line == null ? null : line.trim();
        } catch (Throwable th) {
            return null;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 错误页进程的兜底 handler。
     * <p>
     * 该进程里 {@code CaocInitProvider} 不会运行(ContentProvider 只装在主进程),也没有任何业务
     * UncaughtExceptionHandler —— 错误页自身一崩就是"页面消失 + 系统弹窗"且无栈可查。
     * 这里装上最后一道:留痕后安静结束本进程(主进程已经死了,不必再弹一次系统崩溃框)。
     */
    public static void installErrorProcessFallback(final Context context) {
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            Log.e(TAG, "错误页进程崩溃(主进程不受影响)", throwable);
            appendErrorProcessTrace(context, thread, throwable);
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(10);
        });
    }

    /** 把错误页进程的崩溃栈追加落到私有目录,便于事后排查 */
    private static void appendErrorProcessTrace(Context context, Thread thread, Throwable throwable) {
        if (context == null || throwable == null) return;
        try {
            File file = new File(context.getFilesDir(), ERROR_PROCESS_TRACE);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) return;
            if (file.exists() && file.length() > ERROR_TRACE_MAX) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            pw.println("--- " + new java.util.Date() + " thread=" + (thread == null ? "?" : thread.getName()));
            throwable.printStackTrace(pw);
            pw.flush();
            try (Writer w = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
                w.write(sw.toString());
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // 启动计数 / 安全模式
    // ------------------------------------------------------------------

    /**
     * 读一次崩溃计数,并据此决定本次是否进安全模式(在 {@code App.onCreate} 最前面调用)。
     *
     * <p><b>这里只读、不累加</b>:累加只发生在 {@link #noteCrash(Context)}(真的崩了才记)。
     * 早先的口径是"每次启动都累加,活够 20s 才算健康",于是正常使用里的快速重启 ——
     * 装新包、切主题重载主页、退出去再进来 —— 5 分钟内连做 3 次就会被判成"连续启动失败",
     * 第 3 次起每次启动都弹"已用安全模式启动"(用户口径:"这个老是弹,不是默认主题好像就会
     * 启动 app 就弹")。改成只数崩溃后误报没了,而"启动即崩"的真循环照样熔断:
     * 每次崩溃都会拉起错误页进程,那一趟就是计数的时机(见 {@code App.onCreate} 的错误页分支)。
     */
    public static void noteBootAttempt(Context context) {
        try {
            SharedPreferences sp = prefs(context);
            long now = System.currentTimeMillis();
            migrateIfNeeded(sp);
            int count = Math.max(0, sp.getInt(KEY_ATTEMPTS, 0));
            long safeUntil = sp.getLong(KEY_SAFE_UNTIL, 0L);
            bootAttempts = count;
            safeMode = isSafeModeActive(count, now, safeUntil);
        } catch (Throwable th) {
            bootAttempts = 0;
            safeMode = false;
        }
    }

    /**
     * 记一次**真实崩溃**(错误页进程启动那一趟调用,见 {@code App.onCreate}):
     * 同一 5 分钟窗口内累加,达到阈值即熔断(下次启动进安全模式)。
     */
    public static void noteCrash(Context context) {
        if (context == null) return;
        try {
            SharedPreferences sp = prefs(context);
            long now = System.currentTimeMillis();
            migrateIfNeeded(sp);
            int count = nextAttemptCount(sp.getInt(KEY_ATTEMPTS, 0), sp.getLong(KEY_LAST_AT, 0L), now, WINDOW_MS);
            long safeUntil = nextSafeUntil(count, now, sp.getLong(KEY_SAFE_UNTIL, 0L), SAFE_MODE_COOLDOWN_MS);
            // 必须同步提交:崩溃路径上进程随时会死,异步写会丢计数(熔断就失效了)
            sp.edit()
                    .putInt(KEY_ATTEMPTS, count)
                    .putLong(KEY_LAST_AT, now)
                    .putLong(KEY_SAFE_UNTIL, safeUntil)
                    .putInt(KEY_SCHEMA, SCHEMA)
                    .commit();
            bootAttempts = count;
        } catch (Throwable ignored) {
        }
    }

    /**
     * 老口径(按启动次数)留下的计数一律作废:它会把快速重启误判成失败,用户可能已经卡在
     * 安全模式冷却期里 —— 升级到本口径时清一次,判定从零开始。
     */
    private static void migrateIfNeeded(SharedPreferences sp) {
        if (sp.getInt(KEY_SCHEMA, 1) >= SCHEMA) return;
        sp.edit().remove(KEY_ATTEMPTS).remove(KEY_LAST_AT).remove(KEY_SAFE_UNTIL).putInt(KEY_SCHEMA, SCHEMA).commit();
    }

    /**
     * 本次启动的连续尝试次数。
     * <p>
     * 拆成纯函数是为了可单测(JVM,不碰 Android):窗口外的上一次尝试(正常重启/隔天启动)不参与计数。
     *
     * @param prevCount 上次记录的次数(0=从未记录)
     * @param lastAt    上次尝试时间戳(0=从未记录)
     * @param now       本次启动时间
     * @param windowMs  计数窗口
     */
    static int nextAttemptCount(int prevCount, long lastAt, long now, long windowMs) {
        if (lastAt <= 0L || now - lastAt > windowMs) return 1;
        return Math.max(1, prevCount) + 1;
    }

    /** 连续尝试次数是否已达到进安全模式的阈值(纯判断,供单测) */
    static boolean shouldEnterSafeMode(int attempts) {
        return attempts >= SAFE_MODE_AT;
    }

    /**
     * 安全模式保持到的时间点:达到阈值时开启(或延长到冷却期),之后只随时间自然过期。
     * 已在冷却期内不会重复延长(否则每崩一次就再续 10 分钟,永远出不来)。
     */
    static long nextSafeUntil(int attempts, long now, long safeUntil, long cooldownMs) {
        if (!shouldEnterSafeMode(attempts)) return safeUntil;
        return now >= safeUntil ? now + cooldownMs : safeUntil;
    }

    /** 本次是否按安全模式启动:连续失败达阈值,或仍在安全模式冷却期内(纯判断,供单测) */
    static boolean isSafeModeActive(int attempts, long now, long safeUntil) {
        return shouldEnterSafeMode(attempts) || now < safeUntil;
    }

    /** 本次启动是否处于安全模式(安全模式只降级启动路径,不做任何数据删除) */
    public static boolean isSafeMode() {
        return safeMode;
    }

    /** 本次窗口内的连续启动尝试次数(崩溃页展示用) */
    public static int bootAttempts() {
        return bootAttempts;
    }

    /** 启动后存活够久 → 视为健康启动,清零计数;在 {@code App.onCreate} 里排一次即可 */
    public static void scheduleHealthyMark(final Context context) {
        try {
            new Handler(Looper.getMainLooper()).postDelayed(() -> markHealthy(context), HEALTHY_AFTER_MS);
        } catch (Throwable ignored) {
        }
    }

    private static void markHealthy(Context context) {
        try {
            prefs(context).edit().putInt(KEY_ATTEMPTS, 0).putInt(KEY_SCHEMA, SCHEMA).commit();
            bootAttempts = 0;
        } catch (Throwable ignored) {
        }
    }

    /** 崩溃页"详细错误信息"里的启动状态说明(经 CAOC 的 customCrashDataCollector 传入) */
    public static String describeState() {
        StringBuilder sb = new StringBuilder("5 分钟内连续崩溃: ").append(bootAttempts).append(" 次");
        if (safeMode) {
            sb.append(";本次已进入安全模式(JS 引擎未加载)");
        }
        return sb.toString();
    }

    /**
     * 给 CAOC 用的崩溃数据收集器。
     * <p>
     * 刻意用"具名静态内部类"而不是 lambda:{@code CaocConfig} 本身是 Serializable 并随 Intent
     * 跨进程传到错误页,沿用普通 Java 序列化最省事,也不依赖 lambda 的 {@code $deserializeLambda$}
     * 在压缩混淆后仍然保留(崩溃页是本 App 最后一道兜底,不值得为省几行代码冒这个风险)。
     */
    public static final class CrashStateCollector
            implements cat.ereza.customactivityoncrash.CustomActivityOnCrash.CustomCrashDataCollector {
        private static final long serialVersionUID = 1L;

        @Override
        public String onCrash() {
            return describeState();
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
    }
}
