package com.github.tvbox.osc.transfer;

import android.content.Context;

import com.github.tvbox.osc.config.PrefsDataStore;
import com.github.tvbox.osc.data.AppDataManager;
import com.github.tvbox.osc.share.ShareArchives;
import com.github.tvbox.osc.share.ShareManifest;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Settings and playback-data backup; the independent log database and log files are never included. */
public final class BackupArchive {
    private static final long MAX_BACKUP_BYTES = 1024L * 1024L * 1024L;
    private final Context context;

    public BackupArchive(Context context) { this.context = context.getApplicationContext(); }

    public File create(File backupDirectory) throws Exception {
        if (!backupDirectory.isDirectory() && !backupDirectory.mkdirs()) throw new IOException("无法创建备份目录");
        File work = workDirectory();
        File temporary = null;
        try {
            Map<String, File> entries = new LinkedHashMap<>();
            File prefs = new File(work, ShareManifest.ENTRY_PREFS);
            File room = new File(work, ShareManifest.ENTRY_ROOM);
            // Match configuration-import lock order. Freeze both payloads before doing ZIP work.
            boolean hasRoom = AppDataManager.runOnDb(() -> PrefsDataStore.runWithRollback(() -> {
                try (OutputStream out = new FileOutputStream(prefs)) {
                    out.write(PrefsDataStore.exportJson().getBytes(StandardCharsets.UTF_8));
                }
                if (prefs.length() > 32L * 1024 * 1024) throw new IOException("设置数据超过 32 MB");
                boolean copied = AppDataManager.backup(room);
                if (copied) {
                    if (room.length() > 512L * 1024 * 1024) throw new IOException("数据库超过 512 MB");
                    AppDataManager.validateBackup(room);
                }
                return copied;
            }));
            entries.put(ShareManifest.ENTRY_PREFS, prefs);
            List<String> domains = new ArrayList<>();
            domains.add("prefs");
            if (hasRoom) {
                entries.put(ShareManifest.ENTRY_ROOM, room);
                domains.add("room");
            }
            ShareManifest manifest = new ShareManifest(ShareManifest.SCHEMA_CURRENT,
                    "mbox-backup.zip", 0, "", System.currentTimeMillis(),
                    com.blankj.utilcode.util.AppUtils.getAppVersionName(),
                    com.blankj.utilcode.util.AppUtils.getAppVersionCode(), domains);
            File manifestFile = new File(work, ShareManifest.ENTRY_MANIFEST);
            try (OutputStream out = new FileOutputStream(manifestFile)) {
                out.write(manifest.toJson().getBytes(StandardCharsets.UTF_8));
            }
            entries.put(ShareManifest.ENTRY_MANIFEST, manifestFile);
            temporary = File.createTempFile("mbox_backup_", ".tmp", backupDirectory);
            ShareArchives.zip(entries, temporary);
            if (temporary.length() > MAX_BACKUP_BYTES) throw new IOException("备份包超过 1 GB");
            File target = uniqueBackupFile(backupDirectory);
            if (!temporary.renameTo(target)) throw new IOException("无法保存备份包");
            temporary = null;
            return target;
        } finally {
            if (temporary != null) temporary.delete();
            clean(work);
        }
    }

    /** Source backup is retained; the temporary imported ZIP and unpacked files are always removed. */
    public String restore(File source) throws Exception {
        return restore(source, false);
    }

    /** Protected snapshots replace transferable preferences and preserve an originally absent DB. */
    public String restoreSystem(File source) throws Exception {
        return restore(source, true);
    }

    private String restore(File source, boolean systemSnapshot) throws Exception {
        if (source == null || !source.isFile() || source.length() > MAX_BACKUP_BYTES)
            throw new IOException("备份文件无效或超过 1 GB");
        File copied = File.createTempFile("mbox_restore_", ".zip", context.getCacheDir());
        File work = null;
        try {
            work = workDirectory();
            copy(source, copied, MAX_BACKUP_BYTES);
            ShareManifest manifest = ShareArchives.manifest(copied);
            if (manifest == null || !manifest.isReadableByCurrentVersion()
                    || !"mbox-backup.zip".equals(manifest.fileName())
                    || !manifest.domains().contains("prefs")
                    || manifest.domains().size() > 2
                    || !java.util.Arrays.asList("prefs", "room").containsAll(manifest.domains()))
                throw new IOException("备份包清单无效");
            ShareArchives.check(copied, manifest);
            ShareArchives.extract(copied, work);
            File[] files = work.listFiles();
            if (files == null || files.length != (manifest.domains().contains("room") ? 3 : 2))
                throw new IOException("备份包条目不完整");
            for (File file : files) {
                if (!file.isFile() || !(ShareManifest.ENTRY_PREFS.equals(file.getName())
                        || ShareManifest.ENTRY_ROOM.equals(file.getName())
                        || ShareManifest.ENTRY_MANIFEST.equals(file.getName())))
                    throw new IOException("备份包包含未知条目");
            }
            File prefs = new File(work, ShareManifest.ENTRY_PREFS);
            if (!prefs.isFile() || prefs.length() > 32L * 1024 * 1024) throw new IOException("设置数据缺失或过大");
            String json = new String(read(prefs, 32L * 1024 * 1024), StandardCharsets.UTF_8);
            if (!JsonParser.parseString(json).isJsonObject()) throw new IOException("设置数据格式无效");
            File room = new File(work, ShareManifest.ENTRY_ROOM);
            if (manifest.domains().contains("room") && (!room.isFile() || !sqlite(room)))
                throw new IOException("数据库文件无效");
            boolean roomOk = manifest.domains().contains("room") && AppDataManager.restore(room);
            if (systemSnapshot && !manifest.domains().contains("room")) AppDataManager.restoreMissingDatabase();
            String migrated = BackupSettingsCompat.migrateLegacyVideoPurify(json);
            int count = systemSnapshot ? PrefsDataStore.replaceTransferableJson(migrated)
                    : PrefsDataStore.importJson(migrated);
            if (count < 0) throw new IOException(roomOk
                    ? "数据库已恢复，但设置写入失败，请重试设置恢复" : "设置写入失败");
            if (count <= 0 && !roomOk && !systemSnapshot) throw new IOException("备份中无可恢复数据");
            return "设置/订阅" + (roomOk ? "、播放历史/收藏" : "") + " 已恢复";
        } finally {
            copied.delete();
            if (work != null) clean(work);
        }
    }

    private File workDirectory() throws IOException {
        File dir = new File(context.getCacheDir(), "backup_work_" + UUID.randomUUID());
        if (!dir.mkdir()) throw new IOException("无法创建临时目录");
        return dir;
    }

    private static File uniqueBackupFile(File directory) throws IOException {
        String timestamp = new java.text.SimpleDateFormat("yyyy-MM-dd-HH-mm", java.util.Locale.ROOT)
                .format(new java.util.Date());
        for (int number = 1; number < 10_000; number++) {
            File target = new File(directory, timestamp + (number == 1 ? "" : "-" + number) + ".zip");
            if (!target.exists()) return target;
        }
        throw new IOException("无法为备份生成唯一文件名");
    }

    private static byte[] read(File file, long max) throws IOException {
        if (file.length() > max) throw new IOException("文件过大");
        try (InputStream in = new FileInputStream(file);
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) > 0) {
                if (out.size() + n > max) throw new IOException("文件过大");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static boolean sqlite(File file) throws IOException {
        byte[] header = new byte[16];
        try (InputStream in = new FileInputStream(file)) {
            if (in.read(header) != header.length) return false;
        }
        return "SQLite format 3\u0000".equals(new String(header, StandardCharsets.US_ASCII));
    }

    private static void copy(File source, File target, long max) throws IOException {
        try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192]; int n; long total = 0;
            while ((n = in.read(buffer)) > 0) {
                total += n; if (total > max) throw new IOException("备份包过大");
                out.write(buffer, 0, n);
            }
        }
    }

    private void clean(File dir) {
        try {
            String cache = context.getCacheDir().getCanonicalPath() + File.separator;
            if (!dir.getCanonicalPath().startsWith(cache) || !dir.getName().startsWith("backup_work_")) return;
            cleanChildren(dir);
        } catch (IOException ignored) { }
    }

    private static void cleanChildren(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) cleanChildren(child);
        file.delete();
    }
}
