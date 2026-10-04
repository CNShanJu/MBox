package com.github.tvbox.osc.server;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** 解压先落在同一文件系统的暂存目录，完整校验后才替换目标文件。 */
final class StagedZipExtractor {
    static final Limits DEFAULT_LIMITS = new Limits(256L * 1024 * 1024,
            256L * 1024 * 1024, 1024L * 1024 * 1024, 4096);

    static final class Limits {
        final long archiveBytes;
        final long entryBytes;
        final long totalBytes;
        final int entries;

        Limits(long archiveBytes, long entryBytes, long totalBytes, int entries) {
            this.archiveBytes = archiveBytes;
            this.entryBytes = entryBytes;
            this.totalBytes = totalBytes;
            this.entries = entries;
        }
    }

    static final class InvalidArchiveException extends IOException {
        InvalidArchiveException(String message) { super(message); }
    }

    private StagedZipExtractor() { }

    static void unzip(File archive, File destination) throws IOException {
        unzip(archive, destination, DEFAULT_LIMITS);
    }

    static void unzip(File archive, File destination, Limits limits) throws IOException {
        if (archive == null || !archive.isFile() || archive.length() > limits.archiveBytes) {
            throw new InvalidArchiveException("archive exceeds upload limit");
        }
        File root = requireDirectory(destination);
        File stage = makeWorkDirectory(root, ".mbox-upload-stage-");
        try {
            extract(archive, stage, limits);
            commit(stage, root);
        } finally {
            deleteTree(stage);
        }
    }

    /** 普通上传也先复制完整文件，再以同目录 rename 替换。 */
    static void copyFileAtomically(File source, File target) throws IOException {
        if (source == null || !source.isFile() || target == null || target.getParentFile() == null) {
            throw new IOException("invalid uploaded file");
        }
        File root = requireDirectory(target.getParentFile());
        File stage = makeWorkDirectory(root, ".mbox-upload-stage-");
        try {
            File staged = new File(stage, target.getName());
            try (InputStream in = new BufferedInputStream(new FileInputStream(source));
                 BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(staged))) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            }
            commit(stage, root);
        } finally {
            deleteTree(stage);
        }
    }

    private static void extract(File archive, File stage, Limits limits) throws IOException {
        long total = 0;
        int count = 0;
        Set<String> names = new HashSet<>();
        // ZipInputStream 不会在校验条目数量之前把整个 central directory 建入内存。
        try (ZipInputStream zip = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(archive)))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++count > limits.entries) throw new InvalidArchiveException("too many archive entries");
                String name = safeEntryName(entry.getName(), entry.isDirectory());
                if (!names.add(name)) throw new InvalidArchiveException("duplicate archive entry");
                File staged = childWithin(stage, name);
                if (entry.isDirectory()) {
                    ensureDirectory(staged);
                    zip.closeEntry();
                    continue;
                }
                if (entry.getSize() > limits.entryBytes
                        || (entry.getSize() >= 0 && entry.getSize() > limits.totalBytes - total)) {
                    throw new InvalidArchiveException("archive entry exceeds size limit");
                }
                ensureDirectory(staged.getParentFile());
                long entryBytes = 0;
                try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(staged))) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = zip.read(buffer)) != -1) {
                        if (read > limits.entryBytes - entryBytes || read > limits.totalBytes - total) {
                            throw new InvalidArchiveException("archive expands beyond size limit");
                        }
                        out.write(buffer, 0, read);
                        entryBytes += read;
                        total += read;
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private static String safeEntryName(String raw, boolean directory) throws InvalidArchiveException {
        if (raw == null || raw.isEmpty() || raw.length() > 1024 || raw.indexOf('\0') >= 0
                || raw.startsWith("/") || raw.indexOf('\\') >= 0 || raw.indexOf(':') >= 0) {
            throw new InvalidArchiveException("invalid archive path");
        }
        String name = directory && raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
        String[] parts = name.split("/", -1);
        if (parts.length > 32) throw new InvalidArchiveException("archive path too deep");
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw new InvalidArchiveException("invalid archive path");
            }
        }
        return name;
    }

    private static void commit(File stage, File root) throws IOException {
        List<String> dirs = new ArrayList<>();
        List<String> files = new ArrayList<>();
        collect(stage, "", dirs, files);
        for (String dir : dirs) {
            File target = childWithin(root, dir);
            if (target.exists() && !target.isDirectory()) {
                throw new InvalidArchiveException("archive directory conflicts with file");
            }
        }
        for (String file : files) {
            File target = childWithin(root, file);
            if (target.exists() && !target.isFile()) {
                throw new InvalidArchiveException("archive file conflicts with directory");
            }
        }

        File backup = makeWorkDirectory(root, ".mbox-upload-backup-");
        List<File> createdDirs = new ArrayList<>();
        List<File> installed = new ArrayList<>();
        Map<File, File> saved = new LinkedHashMap<>();
        boolean keepBackup = false;
        try {
            for (String dir : dirs) {
                File target = childWithin(root, dir);
                if (!target.exists()) {
                    ensureDirectory(target);
                    createdDirs.add(target);
                }
            }
            for (String file : files) {
                File target = childWithin(root, file);
                File staged = childWithin(stage, file);
                if (target.exists()) {
                    if (!target.isFile()) throw new IOException("target changed during upload");
                    File old = childWithin(backup, file);
                    ensureDirectory(old.getParentFile());
                    if (!target.renameTo(old)) throw new IOException("cannot back up existing file");
                    saved.put(target, old);
                }
                if (!staged.renameTo(target)) throw new IOException("cannot install uploaded file");
                installed.add(target);
            }
        } catch (IOException failure) {
            IOException rollbackFailure = rollback(installed, saved, createdDirs);
            if (rollbackFailure != null) {
                keepBackup = true;
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        } finally {
            if (!keepBackup) deleteTree(backup);
        }
    }

    private static IOException rollback(List<File> installed, Map<File, File> saved,
                                        List<File> createdDirs) {
        IOException failure = null;
        Collections.reverse(installed);
        for (File file : installed) {
            if (file.exists() && !file.delete() && failure == null) {
                failure = new IOException("cannot remove incomplete uploaded file");
            }
        }
        List<Map.Entry<File, File>> oldFiles = new ArrayList<>(saved.entrySet());
        Collections.reverse(oldFiles);
        for (Map.Entry<File, File> old : oldFiles) {
            File target = old.getKey();
            if (target.exists() && !target.delete() && failure == null) {
                failure = new IOException("cannot clear target during rollback");
            }
            if (!old.getValue().renameTo(target) && failure == null) {
                failure = new IOException("cannot restore original file");
            }
        }
        Collections.reverse(createdDirs);
        for (File dir : createdDirs) {
            if (dir.exists() && !dir.delete() && failure == null) {
                failure = new IOException("cannot remove incomplete uploaded directory");
            }
        }
        return failure;
    }

    private static void collect(File current, String relative, List<String> dirs,
                                List<String> files) throws IOException {
        File[] children = current.listFiles();
        if (children == null) throw new IOException("cannot list staged archive");
        Arrays.sort(children, Comparator.comparing(File::getName));
        for (File child : children) {
            String name = relative.isEmpty() ? child.getName() : relative + "/" + child.getName();
            if (child.isDirectory()) {
                dirs.add(name);
                collect(child, name, dirs, files);
            } else if (child.isFile()) {
                files.add(name);
            } else {
                throw new IOException("unsupported archive entry");
            }
        }
    }

    private static File childWithin(File root, String name) throws IOException {
        File target = new File(root, name);
        String rootPath = root.getCanonicalPath();
        String path = target.getCanonicalPath();
        if (!path.startsWith(rootPath + File.separator)) {
            throw new InvalidArchiveException("archive path escapes target directory");
        }
        return target;
    }

    private static File requireDirectory(File directory) throws IOException {
        if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) {
            throw new IOException("cannot create upload directory");
        }
        return directory.getCanonicalFile();
    }

    private static File makeWorkDirectory(File root, String prefix) throws IOException {
        File candidate = File.createTempFile(prefix, "", root);
        if (!candidate.delete() || !candidate.mkdir()) throw new IOException("cannot stage upload");
        return candidate;
    }

    private static void ensureDirectory(File dir) throws IOException {
        if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) {
            throw new IOException("cannot create archive directory");
        }
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        file.delete();
    }
}
