package com.github.tvbox.osc.data;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;

import androidx.annotation.NonNull;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
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


/**
 * 类描述:
 *
 * @author pj567
 * @since 2020/5/15
 */
public class AppDataManager {
    private static final int DB_FILE_VERSION = 3;
    private static final String DB_NAME = "tvbox";
    private static AppDataManager manager;
    private static AppDataBase dbInstance;

    /**
     * Room 专用单线程执行器:所有 DAO 访问都必须经由 {@link #runOnDb} 提交到该线程执行,
     * 从而彻底移除 allowMainThreadQueries —— 主线程不再执行任何 SQLite 查询/写入,
     * 同时串行化数据库操作,避免多线程并发写导致的锁竞争/卡顿。
     */
    private static final ExecutorService DB_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tvbox-room");
        t.setDaemon(true);
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

    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            // 高频条件列补索引:历史/收藏按 (sourceKey,vodId) 点查、updateTime 排序
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_vodRecord_sourceKey_vodId` ON `vodRecord` (`sourceKey`, `vodId`)");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_vodRecord_updateTime` ON `vodRecord` (`updateTime`)");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_vodCollect_sourceKey_vodId` ON `vodCollect` (`sourceKey`, `vodId`)");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_vodCollect_updateTime` ON `vodCollect` (`updateTime`)");
        }
    };

    static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            try {
                database.execSQL("ALTER TABLE vodRecord ADD COLUMN dataJson TEXT");
            } catch (SQLiteException e) {
                e.printStackTrace();
            }
        }
    };

    static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            try {
                database.execSQL("ALTER TABLE localSource ADD COLUMN type INTEGER NOT NULL DEFAULT 0");
            } catch (SQLiteException e) {
                e.printStackTrace();
            }
        }
    };

    static String dbPath() {
        return DB_NAME + ".v" + DB_FILE_VERSION + ".db";
    }

    /** 在 Room 专用线程上同步执行一个写/读任务(阻塞调用线程,保证串行)。禁止在任务内再次调用本方法(会死锁) */
    public static void runOnDb(Runnable action) {
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

    /** 在 Room 专用线程上同步执行并返回结果(阻塞调用线程,保证串行)。禁止在任务内再次调用本方法(会死锁) */
    public static <T> T runOnDb(Callable<T> action) {
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

    public static AppDataBase get() {
        if (manager == null) {
            throw new RuntimeException("AppDataManager is no init");
        }
        if (dbInstance == null) {
            synchronized (AppDataManager.class) {
                if (dbInstance == null) {
                    dbInstance = Room.databaseBuilder(appContext, AppDataBase.class, dbPath())
                            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                            .addMigrations(MIGRATION_1_2)
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
                if (!cursor.moveToFirst() || cursor.getInt(0) < 1 || cursor.getInt(0) > 2)
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
