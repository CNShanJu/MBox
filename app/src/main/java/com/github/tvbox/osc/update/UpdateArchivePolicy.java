package com.github.tvbox.osc.update;

/** Validate an APK's parsed version against an update provider's optional versionCode. */
final class UpdateArchivePolicy {
    private UpdateArchivePolicy() { }

    static boolean acceptsVersion(int expectedVersion, int archiveVersion) {
        return archiveVersion > 0 && (expectedVersion <= 0 || archiveVersion == expectedVersion);
    }
}
