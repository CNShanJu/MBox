package com.github.tvbox.osc.update;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/** Keep release-provided APK names inside the app's update directory. */
final class UpdateApkFilePolicy {
    private UpdateApkFilePolicy() { }

    static File resolve(File directory, String name) {
        if (directory == null || name == null || name.isEmpty()
                || !name.toLowerCase(Locale.ROOT).endsWith(".apk")
                || name.contains("..") || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                || name.indexOf(':') >= 0 || new File(name).isAbsolute()) return null;
        for (int i = 0; i < name.length(); i++) {
            if (name.charAt(i) < 0x20) return null;
        }
        try {
            File root = directory.getCanonicalFile();
            File target = new File(root, name).getCanonicalFile();
            return root.equals(target.getParentFile()) ? target : null;
        } catch (IOException | SecurityException error) {
            return null;
        }
    }
}
