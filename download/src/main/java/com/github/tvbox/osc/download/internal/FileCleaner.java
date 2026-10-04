package com.github.tvbox.osc.download.internal;

import android.os.Build;
import android.os.Environment;

import android.content.Context;
import com.github.tvbox.osc.bean.DownloadTask;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 文件清理器（File-Cleaner）：临时碎片 / 产物落盘 / 目录回收 / 存储权限 等文件操作。
 * Bug5 增加孤儿 tmpDir 回收；孤儿 tmpDir 增强在后续阶段落地。
 */
public class FileCleaner {

    /** 注入的 application context(独立模块 :download) */
    private static volatile Context appContext;

    static void setAppContext(Context c) {
        appContext = c == null ? null : c.getApplicationContext();
    }

    private FileCleaner() {
    }

    /** 默认目录有私有目录回退，无所有文件访问权限也可入队。 */
    public static boolean hasStoragePermission() {
        return appContext != null;
    }

    /** 旧任务仍可能指向公共目录；权限按实际保存路径检查，私有任务不受撤权影响。 */
    static boolean canWritePath(String path) {
        if (path == null || appContext == null) return false;
        try {
            return DownloadStoragePolicy.canWrite(new File(path), appContext.getFilesDir(),
                    appContext.getExternalFilesDir(null), Build.VERSION.SDK_INT,
                    Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager());
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Bug5: 孤儿 tmpDir 回收——扫描保存根目录下所有 tmp/<任务目录>,
     * 无对应存活任务(含正在写入的任务按快照比对)即递归删除。
     * 调用时机:App 启动 / 下载页打开(任务已加载,安全)。
     */
    static void cleanupOrphanTmpDirs(List<DownloadTask> liveTasks) {
        try {
            Set<String> liveDirs = new HashSet<>();
            if (liveTasks != null) {
                for (DownloadTask t : liveTasks) {
                    if (t.tmpDir != null && !t.tmpDir.isEmpty()) {
                        liveDirs.add(new File(t.tmpDir).getAbsolutePath());
                    }
                }
            }
            scanAndClean(getSaveDir(), liveDirs);          // 公共 Download 下的历史遗留 tmp
            scanAndClean(getPrivateTmpRoot(), liveDirs);   // 私有 tmp 根(当前分片目录)
        } catch (Throwable ignored) {
        }
    }

    private static void scanAndClean(File dir, Set<String> liveDirs) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (!f.isDirectory()) continue;
            if ("tmp".equals(f.getName())) {
                File[] sub = f.listFiles();
                if (sub != null) {
                    for (File s : sub) {
                        if (s.isDirectory() && !liveDirs.contains(s.getAbsolutePath())) {
                            deleteRecursive(s);
                        }
                    }
                }
            } else {
                scanAndClean(f, liveDirs);
            }
        }
    }

    /**
     * 下载保存根目录名:公共 Download/MBox 或 应用专属 Download/MBox。
     * (3.5.x 及以前叫 TVBox,随仓库/App 更名统一改为 MBox;不改名历史目录、也不做迁移——
     *  旧 TVBox 目录里的文件不再被本 App 识别,需要的话手动改名成 MBox 即可,文件内容不用动)
     */
    private static final String SAVE_DIR_NAME = "MBox";

    /** 下载保存根目录:有存储管理权限用公共 Download,否则用应用私有目录 */
    static File getSaveDir() {
        File base;
        if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) {
            base = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SAVE_DIR_NAME);
        } else {
            File ext = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            base = ext == null ? new File(appContext.getFilesDir(), "downloads") : new File(ext, SAVE_DIR_NAME);
        }
        if (!base.exists()) base.mkdirs();
        return base;
    }

    /**
     * HLS 临时分片/合并临时文件私有根目录(app 私有 files/download_tmp):
     * 相册/媒体库看不到(.nomedia 之外再加私有目录隔离),魅族等系统文件管理也不会把
     * 这里的删除收进它自己的回收站——分片清理 = 彻底删除。成品 mp4 仍写公共 {@link #getSaveDir()}。
     */
    static File getPrivateTmpRoot() {
        File base = appContext != null ? appContext.getFilesDir() : getSaveDir();
        File root = new File(base, "download_tmp");
        if (!root.exists()) root.mkdirs();
        return root;
    }

    /**
     * 把"公共 Download 下的旧 HLS tmp 目录"(历史版本产物)迁移到私有根目录:
     * 目录存在则尽量整目录移入(rename,跨分区回退复制+删除);返回迁移后的私有路径。
     * 迁移失败/非公共路径原样返回,保证续传不丢。
     */
    static String migrateTmpDirToPrivate(String legacyTmpDir) {
        if (legacyTmpDir == null || appContext == null) return legacyTmpDir;
        try {
            File publicRoot = getSaveDir().getCanonicalFile();
            File legacy = new File(legacyTmpDir).getCanonicalFile();
            String pubRoot = publicRoot.getPath() + File.separator;
            if (!legacy.getPath().startsWith(pubRoot)) return legacyTmpDir; // 已是私有/其它
            String rel = legacy.getPath().substring(pubRoot.length());
            File privateRoot = getPrivateTmpRoot().getCanonicalFile();
            File target = new File(privateRoot, rel).getCanonicalFile();
            if (!target.getPath().startsWith(privateRoot.getPath() + File.separator)) return legacyTmpDir;
            if (legacy.exists()) {
                File parent = target.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) return legacyTmpDir;
                if (target.exists()) {
                    moveContent(legacy, target); // 同名目标已存在:复制完整后清旧
                } else if (!legacy.renameTo(target)) {
                    moveContent(legacy, target); // 跨分区 rename 失败:复制完整后清旧
                }
            }
            return target.getAbsolutePath();
        } catch (Throwable th) {
            return legacyTmpDir; // 迁移失败:保留原路径(该任务续传仍走旧公共目录,不丢数据)
        }
    }

    /** 仅在所有文件完整复制后删除源目录；失败时旧任务继续使用源目录。 */
    static void moveContent(File srcDir, File dstDir) throws IOException {
        copyContentForMigration(srcDir, dstDir);
        deleteRecursive(srcDir);
    }

    private static void copyContentForMigration(File srcDir, File dstDir) throws IOException {
        rejectSymlink(srcDir);
        rejectSymlink(dstDir);
        if (!srcDir.isDirectory()) throw new IOException("旧临时目录不存在: " + srcDir);
        if (dstDir.exists() ? !dstDir.isDirectory() : !dstDir.mkdirs())
            throw new IOException("无法创建迁移目录: " + dstDir);
        File[] children = srcDir.listFiles();
        if (children == null) throw new IOException("无法读取旧临时目录: " + srcDir);
        for (File c : children) {
            File to = new File(dstDir, c.getName());
            if (c.isDirectory()) {
                copyContentForMigration(c, to);
            } else if (c.isFile()) {
                copyFileForMigration(c, to);
            } else {
                throw new IOException("不支持的迁移条目: " + c);
            }
        }
    }

    private static void copyFileForMigration(File src, File dst) throws IOException {
        rejectSymlink(src);
        rejectSymlink(dst);
        if (dst.exists()) {
            if (!sameFileContent(src, dst)) throw new IOException("迁移目标文件冲突: " + dst);
            return;
        }
        File part = File.createTempFile(".migrate-", ".part", dst.getParentFile());
        try {
            copyFile(src, part);
            if (part.length() != src.length() || !part.renameTo(dst))
                throw new IOException("迁移文件未完整落盘: " + dst);
        } finally {
            if (part.exists()) part.delete();
        }
    }

    private static void rejectSymlink(File file) throws IOException {
        if (!file.getCanonicalFile().equals(file.getAbsoluteFile()))
            throw new IOException("迁移目录中存在软链接: " + file);
    }

    private static boolean sameFileContent(File first, File second) throws IOException {
        if (!second.isFile() || first.length() != second.length()) return false;
        try (FileInputStream a = new FileInputStream(first);
             FileInputStream b = new FileInputStream(second)) {
            byte[] left = new byte[DownloadManager.BUFFER];
            byte[] right = new byte[DownloadManager.BUFFER];
            int n;
            while ((n = a.read(left)) != -1) {
                int done = 0;
                while (done < n) {
                    int read = b.read(right, done, n - done);
                    if (read == -1) return false;
                    done += read;
                }
                for (int i = 0; i < n; i++) if (left[i] != right[i]) return false;
            }
            return b.read() == -1;
        }
    }

    static void deleteQuietly(File f) {
        try {
            if (f != null && f.exists()) f.delete();
        } catch (Throwable ignored) {
        }
    }

    static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs != null) {
                for (File c : fs) deleteRecursive(c);
            }
        }
        f.delete();
    }

    static void copyFile(File src, File dst) throws IOException {
        try (FileInputStream fis = new FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(dst)) {
            byte[] buf = new byte[DownloadManager.BUFFER];
            int n;
            while ((n = fis.read(buf)) != -1) fos.write(buf, 0, n); // != -1:0 不是 EOF
        }
    }

    static void copyFile(File src, OutputStream out) throws IOException {
        FileInputStream fis = new FileInputStream(src);
        byte[] buf = new byte[DownloadManager.BUFFER];
        int n;
        while ((n = fis.read(buf)) != -1) out.write(buf, 0, n); // != -1:0 不是 EOF
        fis.close();
    }
}
