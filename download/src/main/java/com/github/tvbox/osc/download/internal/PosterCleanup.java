package com.github.tvbox.osc.download.internal;

import com.github.tvbox.osc.bean.DownloadTask;
import com.github.tvbox.osc.download.ArchiveItem;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Only removes a shared series poster after every source and every file for that series is gone. */
final class PosterCleanup {
    private PosterCleanup() { }

    static String sanitizeName(String name) {
        if (name == null) return "";
        String sanitized = name.trim().replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return ".".equals(sanitized) || "..".equals(sanitized) ? "" : sanitized;
    }

    static boolean hasReference(String posterKey, List<DownloadTask> tasks, List<ArchiveItem> archive) {
        if (posterKey == null || posterKey.isEmpty()) return false;
        if (tasks != null) for (DownloadTask task : tasks) {
            if (task != null && posterKey.equals(sanitizeName(
                    task.vodName == null ? task.groupName : task.vodName))) return true;
        }
        if (archive != null) for (ArchiveItem item : archive) {
            if (item != null && posterKey.equals(sanitizeName(item.vodName))) return true;
        }
        return false;
    }

    static boolean deleteIfUnused(File filesDir, String vodName, List<DownloadTask> tasks,
                                  List<ArchiveItem> archive, List<File> saveRoots,
                                  File privateTmpRoot, File... lastKnownPaths) {
        String key = sanitizeName(vodName);
        if (filesDir == null || key.isEmpty() || hasReference(key, tasks, archive)) return false;
        try {
            if (lastKnownPaths != null) for (File path : lastKnownPaths) {
                if (path != null && hasContent(path, 0)) return false;
            }
            if (saveRoots != null) for (File root : saveRoots) {
                if (hasSeriesFiles(root, key)) return false;
            }
            if (hasSeriesFiles(privateTmpRoot, key)) return false;
            // Old tasks used download_tmp/tmp/<hash>; their segments.txt carries the series name.
            // Unknown ownership stays conservative, but another series must not block this one.
            if (privateTmpRoot != null
                    && hasLegacySeriesFiles(new File(privateTmpRoot, "tmp"), key)) return false;
            return deletePoster(filesDir, key);
        } catch (IOException | SecurityException ignored) {
            // Inaccessible directories and redirected paths must never authorize deletion.
            return false;
        }
    }

    private static boolean hasSeriesFiles(File root, String key) throws IOException {
        if (root == null || !root.exists()) return false;
        if (!root.isDirectory()) return true;
        File[] sources = root.listFiles();
        if (sources == null) return true;
        File canonicalRoot = root.getCanonicalFile();
        for (File source : sources) {
            if (!source.isDirectory()) continue;
            File canonicalSource = source.getCanonicalFile();
            if (!canonicalRoot.equals(canonicalSource.getParentFile())) return true;
            File series = new File(source, key);
            if (!series.exists()) continue;
            if (!canonicalSource.equals(series.getCanonicalFile().getParentFile())) return true;
            if (hasContent(series, 0)) return true;
        }
        return false;
    }

    private static boolean hasContent(File entry, int depth) throws IOException {
        if (!entry.exists()) return false;
        if (entry.isFile()) return !".nomedia".equals(entry.getName());
        if (!entry.isDirectory() || depth >= 12) return true;
        File[] children = entry.listFiles();
        if (children == null) return true;
        File canonicalParent = entry.getCanonicalFile();
        for (File child : children) {
            if (!canonicalParent.equals(child.getCanonicalFile().getParentFile())) return true;
            if (hasContent(child, depth + 1)) return true;
        }
        return false;
    }

    private static boolean hasLegacySeriesFiles(File root, String key) throws IOException {
        if (!root.exists()) return false;
        if (!root.isDirectory()) return true;
        File[] entries = root.listFiles();
        if (entries == null) return true;
        File canonicalRoot = root.getCanonicalFile();
        for (File entry : entries) {
            if (!canonicalRoot.equals(entry.getCanonicalFile().getParentFile())) return true;
            if (!hasContent(entry, 0)) continue;
            if (!entry.isDirectory()) return true;
            File info = new File(entry, DownloadManager.SEGMENTS_INFO);
            if (!entry.getCanonicalFile().equals(info.getCanonicalFile().getParentFile())) return true;
            String owner = readLegacySeriesKey(info);
            if (owner == null || key.equals(owner)) return true;
        }
        return false;
    }

    /** Read only the short header; missing, malformed, or ambiguous metadata cannot prove ownership. */
    private static String readLegacySeriesKey(File info) throws IOException {
        if (!info.isFile()) return null;
        byte[] header = new byte[4096];
        int length = 0;
        int newlines = 0;
        try (FileInputStream input = new FileInputStream(info)) {
            while (length < header.length && newlines < 4) {
                int n = input.read(header, length, header.length - length);
                if (n < 0) break;
                for (int i = length; i < length + n; i++) {
                    if (header[i] == '\n') newlines++;
                }
                length += n;
            }
        }
        String text = new String(header, 0, length, StandardCharsets.UTF_8);
        int firstEnd = text.indexOf('\n');
        int secondEnd = firstEnd < 0 ? -1 : text.indexOf('\n', firstEnd + 1);
        int thirdEnd = secondEnd < 0 ? -1 : text.indexOf('\n', secondEnd + 1);
        if (thirdEnd < 0 || !text.substring(0, firstEnd).trim().startsWith("来源=")) return null;
        String second = text.substring(firstEnd + 1, secondEnd).trim();
        if (!second.startsWith("剧名=")
                || !text.substring(secondEnd + 1, thirdEnd).trim().startsWith("集数=")) return null;
        String owner = sanitizeName(second.substring("剧名=".length()));
        if (owner.isEmpty()) return null;
        // A newline in a source name can forge a second header; duplicate owner lines are ambiguous.
        int start = thirdEnd + 1;
        for (int end; (end = text.indexOf('\n', start)) >= 0; start = end + 1) {
            if (text.substring(start, end).trim().startsWith("剧名=")) return null;
        }
        return owner;
    }

    private static boolean deletePoster(File filesDir, String key) throws IOException {
        File canonicalFiles = filesDir.getCanonicalFile();
        File root = new File(filesDir, "poster");
        if (!root.isDirectory() || !canonicalFiles.equals(root.getCanonicalFile().getParentFile()))
            return false;
        File directory = new File(root, key);
        if (!directory.isDirectory() || !root.getCanonicalFile().equals(directory.getCanonicalFile().getParentFile()))
            return false;
        File[] children = directory.listFiles();
        if (children == null) return false;
        for (File child : children) {
            if (!directory.getCanonicalFile().equals(child.getCanonicalFile().getParentFile())) return false;
            if (!"poster.jpg".equals(child.getName()) && !"poster.jpg.part".equals(child.getName()))
                return false;
            if (!child.isFile()) return false;
        }
        for (File child : children) {
            if (!child.delete() && child.exists()) return false;
        }
        return directory.delete() || !directory.exists();
    }
}
