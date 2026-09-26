package com.github.tvbox.osc.log.db;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import android.content.Context;

import com.github.tvbox.osc.log.LogEntry;

/**
 * 日志独立数据库（log 模块自包含，不并入 AppDataBase，
 * 为将来拆独立 gradle 模块留好边界）。
 */
@Database(entities = {LogEntry.class}, version = 2, exportSchema = false)
public abstract class LogDatabase extends RoomDatabase {

    public abstract LogDao logDao();

    private static volatile LogDatabase instance;

    /** v1 -> v2:补齐高频查询索引((taskKey,timestamp)、(category,timestamp)),不丢历史日志 */
    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_log_entry_taskKey_timestamp` ON `log_entry` (`taskKey`, `timestamp`)");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_log_entry_category_timestamp` ON `log_entry` (`category`, `timestamp`)");
        }
    };

    public static LogDatabase get(Context context) {
        if (instance == null) {
            synchronized (LogDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(context.getApplicationContext(),
                                    LogDatabase.class, "tvbox_log.db")
                            .addMigrations(MIGRATION_1_2)
                            .fallbackToDestructiveMigration()
                            .build();
                }
            }
        }
        return instance;
    }
}
