package com.github.tvbox.osc.share;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.tvbox.osc.share.internal.ShareArchive;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/** Public archive operations for business packages; transports remain responsible only for bytes. */
public final class ShareArchives {
    private ShareArchives() { }

    @NonNull public static File zip(@NonNull Map<String, File> entries, @NonNull File output)
            throws IOException { return ShareArchive.zip(entries, output); }

    public static void extract(@NonNull File archive, @NonNull File directory) throws IOException {
        ShareArchive.extractTo(archive, directory);
    }

    public static void extract(@NonNull File archive, @NonNull File directory,
                               long maxEntryBytes, long maxTotalBytes) throws IOException {
        ShareArchive.extractTo(archive, directory, maxEntryBytes, maxTotalBytes);
    }

    @Nullable public static ShareManifest manifest(@NonNull File archive) {
        return ShareArchive.readManifest(archive);
    }

    public static void check(@NonNull File archive, @Nullable ShareManifest manifest)
            throws ShareException { ShareArchive.check(archive, manifest); }
}
