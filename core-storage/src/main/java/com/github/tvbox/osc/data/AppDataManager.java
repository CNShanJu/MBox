package com.github.tvbox.osc.data;

import android.content.Context;
import android.database.Cursor;
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
        try {
            return runOnDb(() -> {
                if (dbInstance != null && dbInstance.isOpen()) {
                    dbInstance.close();
                }
                dbInstance = null; // 覆盖文件后置空,下次使用自动重建(新文件)
                File db = dbFile();
                if (db.exists()) {
                    db.delete();
                }
                if (!db.getParentFile().exists())
                    db.getParentFile().mkdirs();
                copyFile(path, db);
                return true;
            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException) throw (IOException) e.getCause();
            throw e;
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
