package com.github.tvbox.osc.log;

import android.content.Context;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.github.tvbox.osc.log.internal.CrashReporter;
import com.github.tvbox.osc.log.internal.LogCollector;
import com.github.tvbox.osc.log.internal.LogFormatter;
import com.github.tvbox.osc.log.internal.LogRepository;
import com.github.tvbox.osc.log.internal.LogcatCapture;
import com.github.tvbox.osc.log.db.LogDatabase;

/**
 * 日志模块门面（唯一对外入口，独立模块，零业务依赖）。
 * <p>
 * 职责收敛为"编排 + 门控"：
 * <ul>
 *   <li>注册：{@link #register(Category, Class)} 返回绑定大类型的限定对象 {@link CategoryLogger}；</li>
 *   <li>门控：enabled / minLevel / 分类开关（{@link #setEnabled}/{@link #setLevel}/{@link #setCategoryEnabled}）；</li>
 *   <li>编排：写入委托 {@link LogCollector}→{@link LogRepository}（Room 结构化业务日志），
 *       原始 logcat 流见 {@link LogcatCapture}，崩溃捕获见 {@link CrashReporter}，展示格式见 {@link LogFormatter}；</li>
 *   <li>查询 / 导出 / 清理 / 崩溃捕获 的门面方法。</li>
 * </ul>
 * 级别常量：{@link #LEVEL_DEBUG} / {@link #LEVEL_INFO} / {@link #LEVEL_WARN} / {@link #LEVEL_ERROR}。
 */
public final class LogStore {

    public static final int LEVEL_DEBUG = 0;
    public static final int LEVEL_INFO = 1;
    public static final int LEVEL_WARN = 2;
    public static final int LEVEL_ERROR = 3;

    private static volatile LogStore instance;
    /** 未初始化时的降级空实现（no-op）：任何模块在 :log 未 init/未引入时调用也安全，日志静默丢弃 */
    private static volatile LogStore noopInstance;

    /** 结构化业务日志仓储（Room；null 即降级模式不落库） */
    private final LogRepository repository;
    /** 采集队列（批量/flush 调度） */
    private final LogCollector collector;
    /** 导出用 cacheDir（仅真实实例持有；noop 模式为 null） */
    private final Context appContext;

    private final ConcurrentHashMap<String, CategoryLogger<?>> loggers = new ConcurrentHashMap<>();
    private final Set<String> disabledCategories = ConcurrentHashMap.newKeySet();

    private volatile boolean enabled = false;
    private volatile int minLevel = LEVEL_INFO;

    /** 由 App 启动时注入版本号（"2.3.1" 或 "2.3.1(44)"）；未注入则空（格式化实现在 LogFormatter） */
    public static void setAppVersion(String version) {
        LogFormatter.setAppVersion(version);
    }

    public static String getAppVersion() {
        return LogFormatter.getAppVersion();
    }

    private LogStore(Context context) {
        this(context, false);
    }

    /** noop=true 时不建数据库（降级空实现，无任何副作用） */
    private LogStore(Context context, boolean noop) {
        LogDatabase db = noop ? null : LogDatabase.get(context);
        repository = new LogRepository(db);
        collector = new LogCollector(repository);
        appContext = noop ? null : context.getApplicationContext();
    }

    /**
     * 门面入口（降级安全）：未 {@link #init(Context)} 时返回 no-op 空实现——
     * 其他模块即使没引入/没初始化 :log 也能正常工作（日志不记录、查询返回空）。
     * 永不返回 null。
     */
    public static LogStore get() {
        if (instance != null) return instance;
        if (noopInstance == null) {
            synchronized (LogStore.class) {
                if (noopInstance == null) {
                    noopInstance = new LogStore(null, true);
                }
            }
        }
        return noopInstance;
    }

    /** App 启动时调用一次；错误日志(logcat)常驻记录,不随开关;开关只控业务日志(Room) */
    public static void init(Context context) {
        if (instance == null) {
            synchronized (LogStore.class) {
                if (instance == null) {
                    instance = new LogStore(context.getApplicationContext());
                }
            }
        }
        LogcatCapture.setAppContext(context); // 独立模块: context 注入,不依赖 app 类
        // 错误日志常驻:App 启动即捕获本应用 logcat ERROR 级(独立于"运行日志"开关,排障随时可查)
        LogcatCapture.start();
        instance.enabled = LogConfig.isEnabled(); // 业务日志开关
        instance.minLevel = LogConfig.getLevel();
        // 业务日志开启期间每天按保留天数清理一次旧业务日志(防止 DB 长期只增不减占用存储)
        instance.repository.scheduleDailyCleanup();
    }

    // ------------------------------------------------------------------
    // 注册
    // ------------------------------------------------------------------

    /** 通用小类型（便捷方法用）：code=generic, label=通用 */
    private enum GenericSubType implements SubType {
        GENERIC("generic", "通用");

        private final String code;
        private final String label;

        GenericSubType(String code, String label) {
            this.code = code;
            this.label = label;
        }

        @Override
        public String code() {
            return code;
        }

        @Override
        public String label() {
            return label;
        }
    }

    private static final CategoryLogger<GenericSubType> genericLogger = new LoggerImpl<>(Category.SYSTEM.name());

    /**
     * 通用业务日志便捷方法（INFO 级别）：任意业务点直接记录，无需自建小类型枚举。
     * 大类型=系统(SYSTEM)，小类型=通用；适合 设置/搜索/播放/收藏/删除/清空 等零散业务操作。
     */
    public static void log(Category category, String detail) {
        try {
            CategoryLogger<GenericSubType> l = category == Category.OTHER ? otherLogger : genericLogger;
            l.info(GenericSubType.GENERIC, detail, null);
        } catch (Throwable ignored) {
        }
    }

    /** 成功事件便捷方法(级别 INFO,结果=SUCCESS):补录"某操作成功",任意业务点直接调用 */
    public static void success(Category category, String detail) {
        try {
            CategoryLogger<GenericSubType> l = category == Category.OTHER ? otherLogger : genericLogger;
            l.success(GenericSubType.GENERIC, detail, null);
        } catch (Throwable ignored) {
        }
    }

    /** 失败事件便捷方法(级别 ERROR,结果=FAILURE):记录失败原因,可在日志页"仅失败"筛出 */
    public static void fail(Category category, String detail) {
        try {
            CategoryLogger<GenericSubType> l = category == Category.OTHER ? otherLogger : genericLogger;
            l.fail(GenericSubType.GENERIC, detail, null);
        } catch (Throwable ignored) {
        }
    }

    private static final CategoryLogger<GenericSubType> otherLogger = new LoggerImpl<>(Category.OTHER.name());

    /**
     * 注册大类型，返回绑定该大类型的限定对象（幂等：同大类型返回同一实例）。
     * 模块 init 时调用一次并持有返回值；之后所有日志调用自动带大类型。
     */
    public <T extends Enum<T> & SubType> CategoryLogger<T> register(Category category, Class<T> subTypeClass) {
        return register(category.name(), subTypeClass);
    }

    /** 动态注册大类型（字符串，扩展用） */
    public <T extends Enum<T> & SubType> CategoryLogger<T> register(String categoryName, Class<T> subTypeClass) {
        @SuppressWarnings("unchecked")
        CategoryLogger<T> logger = (CategoryLogger<T>) loggers.get(categoryName);
        if (logger == null) {
            logger = new LoggerImpl<>(categoryName);
            CategoryLogger<?> old = loggers.putIfAbsent(categoryName, logger);
            if (old != null) {
                @SuppressWarnings("unchecked")
                CategoryLogger<T> cast = (CategoryLogger<T>) old;
                return cast;
            }
        }
        return logger;
    }

    // ------------------------------------------------------------------
    // 写入（LoggerImpl 调用；也供 CrashReporter 等内部场景使用）
    // ------------------------------------------------------------------

    void log(String categoryName, int level, String subTypeCode, String subTypeLabel,
             String detail, String result, String reason, JSONObject extras, String taskKey) {
        log(categoryName, level, subTypeCode, subTypeLabel, detail, result, reason, extras, taskKey, false);
    }

    /** force=true 绕过业务日志开关(enabled)门控——崩溃等关键排障信息始终落库 */
    void log(String categoryName, int level, String subTypeCode, String subTypeLabel,
             String detail, String result, String reason, JSONObject extras, String taskKey, boolean force) {
        if (!force) {
            if (!enabled) return;
            if (level < minLevel) return;
        }
        if (disabledCategories.contains(categoryName)) return;
        LogEntry e = new LogEntry();
        e.timestamp = System.currentTimeMillis();
        e.category = categoryName;
        e.subTypeCode = subTypeCode;
        e.subTypeLabel = subTypeLabel;
        e.level = level;
        e.detail = LogFormatter.truncate(detail, LogFormatter.MAX_DETAIL);
        e.result = result;
        e.reason = LogFormatter.truncate(reason, LogFormatter.MAX_REASON);
        e.extras = extras != null ? extras.toString() : null;
        if (taskKey == null && extras != null) {
            taskKey = extras.optString("episodeId", null);
            if (taskKey != null && taskKey.isEmpty()) taskKey = null;
        }
        e.taskKey = taskKey;
        collector.offer(e);
    }

    // ------------------------------------------------------------------
    // 查询（读通道与写通道分离，不排队等写；降级模式返回 null）
    // ------------------------------------------------------------------

    /** 组合筛选查询（阻塞至结果返回；页面调用建议放后台线程）；降级模式返回 null */
    @Nullable
    public List<LogEntry> query(final LogFilter f) {
        return repository.query(f);
    }

    /** 任务维度视图（"任务详情→查看日志"）；降级模式返回 null */
    @Nullable
    public List<LogEntry> queryByTask(String taskKey, int limit, int offset) {
        return repository.queryByTask(taskKey, limit, offset);
    }

    // ------------------------------------------------------------------
    // 开关 / 级别
    // ------------------------------------------------------------------

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 业务日志开关（由 LogConfig 驱动；只控 Room 结构化业务日志）。
     * 错误日志(logcat)不受此开关影响:init 时已常驻启动。
     */
    public void setEnabled(boolean on) {
        enabled = on;
        if (on) {
            repository.scheduleDailyCleanup();
        }
    }

    public int getLevel() {
        return minLevel;
    }

    public void setLevel(int level) {
        minLevel = Math.max(LEVEL_DEBUG, Math.min(LEVEL_ERROR, level));
    }

    /** 按大类型动态开关（如线上按需开 下载 DEBUG） */
    public void setCategoryEnabled(Category category, boolean on) {
        if (on) {
            disabledCategories.remove(category.name());
        } else {
            disabledCategories.add(category.name());
        }
    }

    // ------------------------------------------------------------------
    // 运维
    // ------------------------------------------------------------------

    /** 按保留天数清理（默认 7 天；只删日志，不碰任务元数据与档案）；降级模式空操作 */
    public void clear(int retentionDays) {
        repository.deleteBeforeAsync(retentionDays);
    }

    /** 清空全部业务日志；降级模式空操作 */
    public void clearAll() {
        repository.clearAllAsync();
    }

    /** 按当前筛选导出 txt（放 cacheDir，可 FileProvider 分享）；无结果/降级模式返回 null */
    @Nullable
    public File export(LogFilter f) {
        if (repository.isNoop()) return null;
        List<LogEntry> entries = query(f);
        if (entries == null || entries.isEmpty()) return null;
        try {
            File out = new File(appContext.getCacheDir(), "log_export_" + System.currentTimeMillis() + ".txt");
            FileWriter fw = new FileWriter(out);
            try {
                for (LogEntry e : entries) {
                    fw.write(LogFormatter.formatEntry(e) + "\n");
                }
            } finally {
                fw.close();
            }
            return out;
        } catch (Throwable th) {
            android.util.Log.e("LogStore", "日志导出失败", th);
            return null;
        }
    }

    /** 崩溃捕获：委托 CrashReporter（过滤无害系统异常 → 结构化落库 + 立即 flush → 转交原 handler）。
     *  崩溃始终落库(force),不受业务日志开关影响——排障关键信息不丢 */
    public void installCrashHandler() {
        CrashReporter.install((detail, reason) -> {
            try {
                log(Category.SYSTEM.name(), LEVEL_ERROR, "crash", "崩溃",
                        detail, "FAILURE", reason, null, null, true);
                collector.flushNow();
            } catch (Throwable ignored) {
            }
        });
    }

    // ------------------------------------------------------------------
    // 错误日志文件（Tab2 错误日志: logcat 本应用 E 级错误流; 只含 logcat-* 文件）
    // 门面委托 LogcatCapture, 页面只依赖本门面, 不再直接碰 common.AppLog
    // ------------------------------------------------------------------

    /** 列出 logcat 错误日志按天/分段文件（新在前）；未 init/降级返回空表 */
    public List<File> listRawLogFiles() {
        return LogcatCapture.listLogFiles();
    }

    /** 读文件末尾最近 maxLines 行（大文件安全）；文件不存在返回空表 */
    public List<String> readRawLogTail(File file, int maxLines) {
        return LogcatCapture.readTail(file, maxLines);
    }

    /** 清空原始日志文件；未 init/降级空操作 */
    public void clearRawLogFiles() {
        LogcatCapture.clearAll();
    }

    /** 导出全部原始日志文件到一个 txt（cacheDir，可 FileProvider 分享）；无文件/未 init 返回 null */
    @Nullable
    public File exportRawLogFiles() {
        return LogcatCapture.exportAll();
    }

    // ------------------------------------------------------------------
    // 展示（格式化委托 LogFormatter）
    // ------------------------------------------------------------------

    /** 日志页一行展示：[版本] [时间] [大类型] [小类型] 干了啥 ✓/✗ */
    public String formatEntry(LogEntry e) {
        return LogFormatter.formatEntry(e);
    }

    /** 级别常量 → 名称 */
    public static String levelName(int level) {
        return LogFormatter.levelName(level);
    }

    /** LoggerImpl：CategoryLogger 的默认实现，大类型由构造注入 */
    private static final class LoggerImpl<T extends Enum<T> & SubType> implements CategoryLogger<T> {
        private final String categoryName;

        LoggerImpl(String categoryName) {
            this.categoryName = categoryName;
        }

        @Override
        public void success(T subType, String detail, JSONObject extras) {
            get().log(categoryName, LEVEL_INFO, subType.code(), subType.label(), detail, "SUCCESS", null, extras, null);
        }

        @Override
        public void fail(T subType, String detail, JSONObject extras) {
            get().log(categoryName, LEVEL_ERROR, subType.code(), subType.label(), detail, "FAILURE", detail, extras, null);
        }

        @Override
        public void fail(T subType, String detail, Throwable t, JSONObject extras) {
            StringBuilder reason = new StringBuilder();
            if (detail != null) reason.append(detail);
            reason.append(" | ").append(t == null ? "null" : t);
            Throwable cause = t;
            int lines = 0;
            while (cause != null && cause.getStackTrace() != null && lines < 40) {
                for (StackTraceElement el : cause.getStackTrace()) {
                    reason.append('\n').append("    at ").append(el);
                    if (++lines >= 40) break;
                }
                cause = cause.getCause();
            }
            get().log(categoryName, LEVEL_ERROR, subType.code(), subType.label(), detail, "FAILURE", reason.toString(), extras, null);
        }

        @Override
        public void info(T subType, String detail, JSONObject extras) {
            get().log(categoryName, LEVEL_INFO, subType.code(), subType.label(), detail, "INFO", null, extras, null);
        }

        @Override
        public void warn(T subType, String detail, JSONObject extras) {
            get().log(categoryName, LEVEL_WARN, subType.code(), subType.label(), detail, "INFO", null, extras, null);
        }

        @Override
        public void debug(T subType, String detail, JSONObject extras) {
            get().log(categoryName, LEVEL_DEBUG, subType.code(), subType.label(), detail, "INFO", null, extras, null);
        }
    }
}
