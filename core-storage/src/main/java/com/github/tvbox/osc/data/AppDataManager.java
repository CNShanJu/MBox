package com.github.tvbox.osc.data;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;

import androidx.annotation.NonNull;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.sqlite.db.SupportSQLiteDatabase;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * 类描述:
 *
 * @author pj567
 * @since 2020/5/15
 */
public class AppDataManager {
    // 换文件策略:每次 schema 变更升此值换新文件名(新库 onCreate 建全表),不写 Room Migration。
    // 老版本数据库文件残留为孤儿、不再读取;历史/收藏随换库清空(用户确认放弃老数据兼容)。
    private static final int DB_FILE_VERSION = 4;
    private static final String DB_NAME = "tvbox";
    /** 匹配历史库文件 tvbox.v<N>.db 及其 -journal/-wal/-shm sidecar */
    private static final Pattern LEGACY_DB_PATTERN =
            Pattern.compile(Pattern.quote(DB_NAME) + "\\.v(\\d+)\\.db(?:-(?:journal|wal|shm))?");
    private static AppDataManager manager;
    private static AppDataBase dbInstance;
    private static volatile Thread dbThread;

    /**
     * Room 专用单线程执行器:所有 DAO 访问都必须经由 {@link #runOnDb} 提交到该线程执行,
     * 从而彻底移除 allowMainThreadQueries —— 主线程不再执行任何 SQLite 查询/写入,
     * 同时串行化数据库操作,避免多线程并发写导致的锁竞争/卡顿。
     */
    private static final ExecutorService DB_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tvbox-room");
        t.setDaemon(true);
        dbThread = t;
        return t;
    });

    /** 注入的 application context(替代原对 App 单例的依赖,便于 storage 独立成模块) */
    private static volatile Context appContext;

    private AppDataManager() {
    }

    /** App.onCreate 时注入 context 并初始化 */
    public static void init(Context context) {
        appContext = context == null ? null : context.getApplicationContext();
        if (manager == null) {
            synchronized (AppDataManager.class) {
                if (manager == null) {
                    manager = new AppDataManager();
                }
            }
        }
    }

    private static File dbFile() {
        if (appContext == null) {
            throw new RuntimeException("AppDataManager is no init");
        }
        return appContext.getDatabasePath(dbPath());
    }

    static String dbPath() {
        return DB_NAME + ".v" + DB_FILE_VERSION + ".db";
    }

    /** 清理旧代数据库文件(换文件策略下,DB_FILE_VERSION 之前的历史库不再使用)。
     *  同步执行,调用方应在后台线程调用;删除失败静默(下次启动再试),不影响当前库。 */
    public static void cleanupLegacyDatabaseFiles() {
        File dbDir = dbFile().getParentFile();
        if (dbDir == null || !dbDir.isDirectory()) return;
        File[] files = dbDir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (!f.isFile() || !isLegacyDbFile(f.getName())) continue;
            f.delete();
        }
    }

    private static boolean isLegacyDbFile(String name) {
        Matcher m = LEGACY_DB_PATTERN.matcher(name);
        if (!m.matches()) return false;
        try {
            return Integer.parseInt(m.group(1)) < DB_FILE_VERSION;
        } catch (NumberFormatException e) {
            return false; // 版本号溢出等极端情况,不当旧库处理
        }
    }

    /** 在 Room 专用线程上同步执行一个写/读任务(阻塞其他线程,保证串行)；专用线程内可重入。 */
    public static void runOnDb(Runnable action) {
        if (Thread.currentThread() == dbThread) {
            action.run();
            return;
        }
        try {
            DB_EXECUTOR.submit(action).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new RuntimeException(cause == null ? e : cause);
        }
    }

    /** 不等待结果地按提交顺序执行数据库写入；供 UI 发起的历史保存使用。 */
    public static void executeOnDb(Runnable action) {
        DB_EXECUTOR.execute(action);
    }

    /** 在 Room 专用线程上同步执行并返回结果(阻塞其他线程,保证串行)；专用线程内可重入。 */
    public static <T> T runOnDb(Callable<T> action) {
        if (Thread.currentThread() == dbThread) return callUnchecked(action);
        Future<T> future = DB_EXECUTOR.submit(action);
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new RuntimeException(cause == null ? e : cause);
        }
    }

    /** 操作已成功但事务提交标记或结束失败，数据库提交状态无法确认。 */
    public static final class TransactionCompletionException extends IllegalStateException {
        private TransactionCompletionException(Throwable cause) {
            super("数据库事务提交或结束失败，提交状态需要检查", cause);
        }
    }

    /**
     * 在 Room 专用线程上运行一个数据库事务。操作失败时不标记成功，由 Room 回滚；
     * 受检异常以 RuntimeException 包装并保留原始 cause。事务结束失败时提交状态需检查。
     */
    public static <T> T runInTransaction(Callable<T> action) {
        if (action == null) throw new IllegalArgumentException("事务操作不能为空");
        return runOnDb(() -> {
            AppDataBase db = get();
            db.beginTransaction();
            Throwable failure = null;
            try {
                T result = callUnchecked(action);
                try {
                    db.setTransactionSuccessful();
                } catch (RuntimeException | Error completionError) {
                    throw new TransactionCompletionException(completionError);
                }
                return result;
            } catch (RuntimeException | Error error) {
                failure = error;
                throw error;
            } finally {
                try {
                    db.endTransaction();
                } catch (RuntimeException | Error endError) {
                    if (failure != null) failure.addSuppressed(endError);
                    else throw new TransactionCompletionException(endError);
                }
            }
        });
    }

    private static <T> T callUnchecked(Callable<T> action) {
        try {
            return action.call();
        } catch (RuntimeException | Error error) {
            throw error;
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }

    public static AppDataBase get() {
        if (manager == null) {
            throw new RuntimeException("AppDataManager is no init");
        }
        if (dbInstance == null) {
            synchronized (AppDataManager.class) {
                if (dbInstance == null) {
                    dbInstance = Room.databaseBuilder(appContext, AppDataBase.class, dbPath())
                            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                            .addCallback(new RoomDatabase.Callback() {
                                @Override
                                public void onCreate(@NonNull SupportSQLiteDatabase db) {
                                    super.onCreate(db);
                                }

                                @Override
                                public void onOpen(@NonNull SupportSQLiteDatabase db) {
                                    super.onOpen(db);
                                }
                            })
                            // 禁止主线程查询:所有 DAO 访问统一走 runOnDb 提交到专用线程(见 DB_EXECUTOR)
                            .build();
                }
            }
        }
        return dbInstance;
    }

    public static boolean backup(final File path) throws IOException {
        try {
            return runOnDb(() -> {
                if (dbInstance != null && dbInstance.isOpen()) {
                    dbInstance.close();
                }
                dbInstance = null; // 关闭后置空,下次使用自动重建
                File db = dbFile();
                if (db.exists()) {
                    copyFile(db, path);
                    return true;
                } else {
                    return false;
                }
            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException) throw (IOException) e.getCause();
            throw e;
        }
    }

    /** 是否存在应用数据库文件(备份/保护快照判断"有无历史/收藏数据"用;不打开数据库)。 */
    public static boolean hasDatabaseFile() {
        return dbFile().exists();
    }

    /** 校验已生成的数据库备份；不修改运行中的 Room 数据库。 */
    public static void validateBackup(File path) throws IOException {
        if (path == null || !path.isFile() || path.length() == 0
                || path.length() > 512L * 1024L * 1024L)
            throw new IOException("备份数据库不存在或超过 512 MB");
        validateRestoreDatabase(path);
    }

    /** 将恢复前原本不存在的数据库恢复为缺失状态，且只触及本应用数据库及其 sidecar。 */
    public static void restoreMissingDatabase() throws IOException {
        try {
            runOnDb(() -> {
                if (dbInstance != null) dbInstance.close();
                dbInstance = null;
                File db = dbFile();
                deleteDatabaseFile(new File(db.getPath() + "-journal"));
                deleteDatabaseFile(new File(db.getPath() + "-wal"));
                deleteDatabaseFile(new File(db.getPath() + "-shm"));
                deleteDatabaseFile(db);
                return null;
            });
        } catch (RuntimeException error) {
            if (error.getCause() instanceof IOException) throw (IOException) error.getCause();
            throw new IOException("无法恢复数据库缺失状态", error);
        }
    }

    private static void deleteDatabaseFile(File file) throws IOException {
        if (file.exists() && !file.delete())
            throw new IOException("无法删除数据库文件: " + file.getName());
    }

    public static boolean restore(final File path) throws IOException {
        if (path == null || !path.isFile() || path.length() == 0
                || path.length() > 512L * 1024L * 1024L)
            throw new IOException("备份数据库不存在或超过 512 MB");
        try {
            return runOnDb(() -> {
                File db = dbFile();
                File parent = db.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("无法创建数据库目录");
                File staged = File.createTempFile("tvbox-restore-", ".db", parent);
                File previous = null;
                try {
                    copyFile(path, staged);
                    validateRestoreDatabase(staged);
                    // 校验与复制完成前不碰现有数据库；替换在 Room 的专用线程上串行执行。
                    if (dbInstance != null) dbInstance.close();
                    dbInstance = null;
                    if (db.exists()) {
                        previous = File.createTempFile("tvbox-previous-", ".db", parent);
                        if (!previous.delete() || !db.renameTo(previous))
                            throw new IOException("无法暂存原数据库");
                    }
                    if (!staged.renameTo(db)) {
                        if (previous != null && !previous.renameTo(db))
                            throw new IOException("数据库替换失败，原数据库位于 " + previous.getName());
                        previous = null;
                        throw new IOException("无法替换数据库");
                    }
                    // TRUNCATE 模式仍可能留下空 journal；旧 sidecar 不能配新数据库使用。
                    new File(db.getPath() + "-journal").delete();
                    new File(db.getPath() + "-wal").delete();
                    new File(db.getPath() + "-shm").delete();
                    return true;
                } finally {
                    staged.delete();
                    if (previous != null && db.exists()) previous.delete();
                }
            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException) throw (IOException) e.getCause();
            throw e;
        }
    }

    private static void validateRestoreDatabase(File file) throws IOException {
        try (SQLiteDatabase sqlite = SQLiteDatabase.openDatabase(
                file.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
            try (Cursor cursor = sqlite.rawQuery("PRAGMA quick_check(1)", null)) {
                if (!cursor.moveToFirst() || !"ok".equalsIgnoreCase(cursor.getString(0)))
                    throw new IOException("备份数据库校验失败");
            }
            try (Cursor cursor = sqlite.rawQuery("PRAGMA user_version", null)) {
                // 只接受当前代库(schema v4)的备份;旧代库(v1~v3)不再兼容
                if (!cursor.moveToFirst() || cursor.getInt(0) != 4)
                    throw new IOException("备份数据库版本不受支持");
            }
            try (Cursor cursor = sqlite.rawQuery(
                    "SELECT count(*) FROM sqlite_master WHERE type='table' AND name IN "
                            + "('cache', 'vodRecord', 'vodCollect', 'room_master_table')", null)) {
                if (!cursor.moveToFirst() || cursor.getInt(0) != 4)
                    throw new IOException("备份数据库表结构不完整");
            }
        } catch (SQLiteException error) {
            throw new IOException("备份数据库无法读取", error);
        }
    }

    /** 本地文件复制(替代对 app/spider 模块 FileUtils 的依赖,便于 storage 独立成模块) */
    private static void copyFile(File src, File dst) throws IOException {
        try (InputStream is = new FileInputStream(src); OutputStream os = new FileOutputStream(dst)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) > 0) {
                os.write(buffer, 0, len);
            }
        }
    }
}
