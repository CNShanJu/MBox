package com.github.tvbox.osc.api;

import java.io.File;
import java.io.IOException;

/** Keeps the previous main jar until the replacement has been loaded successfully. */
final class JarCacheFiles {
    private JarCacheFiles() {
    }

    /** Restores a replacement interrupted before commit. Call before checking or loading the cache. */
    static void recover(File cache) throws IOException {
        File target = cache.getAbsoluteFile();
        File backup = backupFor(target);
        if (!backup.exists()) return;
        if (!backup.isFile()) throw new IOException("jar backup is not a file");
        if (target.exists()) {
            if (!target.isFile() || !target.delete()) {
                throw new IOException("cannot remove uncommitted jar cache");
            }
        }
        if (!backup.renameTo(target)) {
            throw new IOException("cannot restore previous jar cache");
        }
    }

    /**
     * Installs the download at the canonical cache path so JarLoader uses its normal path identity.
     * The caller must commit after a successful load or roll back after a failed/obsolete load.
     */
    static Replacement replace(File download, File cache) throws IOException {
        File source = download.getAbsoluteFile();
        File target = cache.getAbsoluteFile();
        if (!source.isFile()) throw new IOException("jar download is missing");
        if (source.getCanonicalFile().equals(target.getCanonicalFile())
                || !source.getParentFile().getCanonicalFile()
                        .equals(target.getParentFile().getCanonicalFile())) {
            throw new IOException("jar download must be a different file in the cache directory");
        }
        if (target.exists() && !target.isFile()) throw new IOException("jar cache is not a file");

        recover(target);
        File backup = backupFor(target);
        boolean hadCache = target.exists();
        if (hadCache && !target.renameTo(backup)) {
            throw new IOException("cannot preserve jar cache");
        }
        if (!source.renameTo(target)) {
            IOException error = new IOException("cannot replace jar cache");
            if (hadCache && !backup.renameTo(target)) {
                error.addSuppressed(new IOException("cannot restore previous jar cache"));
            }
            throw error;
        }
        return new Replacement(target, backup, hadCache);
    }

    private static File backupFor(File target) {
        return new File(target.getParentFile(), target.getName() + ".previous");
    }

    static final class Replacement {
        private final File target;
        private final File backup;
        private final boolean hadCache;
        private boolean finished;

        private Replacement(File target, File backup, boolean hadCache) {
            this.target = target;
            this.backup = backup;
            this.hadCache = hadCache;
        }

        void commit() throws IOException {
            if (finished) throw new IllegalStateException("jar replacement already finished");
            if (hadCache && !backup.delete()) {
                throw new IOException("cannot remove previous jar cache");
            }
            finished = true;
        }

        void rollback() throws IOException {
            if (finished) throw new IllegalStateException("jar replacement already finished");
            if (hadCache) {
                recover(target);
            } else if (target.exists() && (!target.isFile() || !target.delete())) {
                throw new IOException("cannot remove failed jar cache");
            }
            finished = true;
        }
    }
}
