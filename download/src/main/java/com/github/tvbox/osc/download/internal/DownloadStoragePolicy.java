package com.github.tvbox.osc.download.internal;

import java.io.File;
import java.io.IOException;

/** 权限跟随最终目录，规范化路径后才判定私有目录。 */
public final class DownloadStoragePolicy {
    private DownloadStoragePolicy() {}
    public static boolean canWrite(File file, File internal, File external, int sdk, boolean allFiles) throws IOException {
        if (inside(file, internal) || (external != null && inside(file, external))) return true;
        return sdk < 30 || allFiles;
    }
    private static boolean inside(File file, File root) throws IOException {
        String parent = root.getCanonicalPath(), child = file.getCanonicalPath();
        return child.equals(parent) || child.startsWith(parent + File.separator);
    }
}
