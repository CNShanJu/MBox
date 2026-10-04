package com.github.tvbox.osc.update;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.zip.ZipFile;

/** Validate an APK's parsed version against an update provider's optional versionCode. */
final class UpdateArchivePolicy {
    private UpdateArchivePolicy() { }

    /** Debug installs use a .debug suffix, while release assets keep the base application ID. */
    static boolean acceptsPackageName(String installedPackage, String archivePackage) {
        if (installedPackage == null || archivePackage == null || archivePackage.isEmpty()) return false;
        if (installedPackage.equals(archivePackage)) return true;
        String debugSuffix = ".debug";
        return installedPackage.endsWith(debugSuffix)
                && installedPackage.substring(0, installedPackage.length() - debugSuffix.length())
                        .equals(archivePackage);
    }

    static boolean acceptsVersion(int expectedVersion, int archiveVersion) {
        return archiveVersion > 0 && (expectedVersion <= 0 || archiveVersion == expectedVersion);
    }

    /** A short APK response with a ZIP header but no ZIP directory can still be resumed. */
    static boolean isLikelyInterruptedApk(File file) {
        if (file == null || file.length() < 4) return false;
        try (FileInputStream input = new FileInputStream(file)) {
            if (input.read() != 'P' || input.read() != 'K' || input.read() != 3 || input.read() != 4) {
                return false;
            }
        } catch (IOException error) {
            return false;
        }
        try (ZipFile ignored = new ZipFile(file)) {
            return false;
        } catch (IOException incompleteZip) {
            return true;
        }
    }
}
