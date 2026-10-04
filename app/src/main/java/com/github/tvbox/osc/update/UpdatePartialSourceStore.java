package com.github.tvbox.osc.update;

import android.util.AtomicFile;

import com.google.gson.Gson;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Persists the source of an incomplete APK so a new process can resume its own bytes. */
final class UpdatePartialSourceStore {
    private static final int MAX_RECORD_BYTES = 64 * 1024;
    private static final Gson GSON = new Gson();

    private UpdatePartialSourceStore() { }

    /** Record ownership before writing bytes; there is only one active update download. */
    static void remember(File file, UpdateInfo info, String url) {
        Record record = record(file, info, url);
        if (record == null) throw new IllegalArgumentException("Invalid APK partial source");
        AtomicFile metadata = metadata(file);
        FileOutputStream output = null;
        try {
            output = metadata.startWrite();
            output.write(encode(record).getBytes(StandardCharsets.UTF_8));
            metadata.finishWrite(output);
        } catch (IOException | RuntimeException error) {
            if (output != null) metadata.failWrite(output);
            // No APK bytes may be written unless their source is durable.
            throw new IllegalStateException("Cannot persist APK partial source", error);
        }
    }

    /** Return null for an absent, empty, stale, or untrusted partial. */
    static String restore(File file, UpdateInfo info) {
        if (file == null) return null;
        try (InputStream input = metadata(file).openRead();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_RECORD_BYTES) return null;
                output.write(buffer, 0, count);
            }
            return matchingSource(decode(new String(output.toByteArray(), StandardCharsets.UTF_8)), file, info);
        } catch (IOException | RuntimeException error) {
            return null;
        }
    }

    /** Remove both the current metadata and AtomicFile's recovery files. */
    static void clear(File file) {
        if (file == null) return;
        File base = metadataFile(file);
        new AtomicFile(base).delete();
        new File(base.getPath() + ".bak").delete();
        new File(base.getPath() + ".new").delete();
    }

    private static AtomicFile metadata(File file) {
        return new AtomicFile(metadataFile(file));
    }

    private static File metadataFile(File file) {
        return new File(file.getAbsolutePath() + ".source");
    }

    static Record record(File file, UpdateInfo info, String url) {
        if (file == null || info == null || url == null || url.isEmpty()
                || !info.downloadUrls.contains(url)) return null;
        return new Record(file.getAbsolutePath(), info.versionName, info.versionTag,
                info.versionCode, info.apkSize, url);
    }

    static String encode(Record record) {
        return GSON.toJson(record);
    }

    static Record decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) return null;
        try {
            return GSON.fromJson(encoded, Record.class);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    static String matchingSource(Record record, File file, UpdateInfo info) {
        if (record == null || file == null || info == null || !file.isFile()
                || file.length() <= 0 || (info.apkSize > 0 && file.length() > info.apkSize)
                || !file.getAbsolutePath().equals(record.path)
                || !Objects.equals(info.versionName, record.versionName)
                || !Objects.equals(info.versionTag, record.versionTag)
                || info.versionCode != record.versionCode || info.apkSize != record.apkSize
                || record.url == null || !info.downloadUrls.contains(record.url)) return null;
        return record.url;
    }

    static final class Record {
        final String path;
        final String versionName;
        final String versionTag;
        final int versionCode;
        final long apkSize;
        final String url;

        Record(String path, String versionName, String versionTag, int versionCode,
               long apkSize, String url) {
            this.path = path;
            this.versionName = versionName;
            this.versionTag = versionTag;
            this.versionCode = versionCode;
            this.apkSize = apkSize;
            this.url = url;
        }
    }
}
