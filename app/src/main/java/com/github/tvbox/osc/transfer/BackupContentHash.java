package com.github.tvbox.osc.transfer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Gson;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** SHA-256 of backup data, independent of ZIP timestamps and manifest export metadata. */
public final class BackupContentHash {
    private static final String PREFS = "prefs.json";
    private static final String ROOM = "room.db";
    private static final String MANIFEST = "manifest.json";
    private static final long MAX_ZIP_BYTES = 1024L * 1024L * 1024L;
    private static final int MAX_PREFS_BYTES = 32 * 1024 * 1024;
    private static final long MAX_ROOM_BYTES = 512L * 1024L * 1024L;
    private static final byte[] VERSION = "mbox-backup-content-v1\u0000".getBytes(StandardCharsets.US_ASCII);
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final Gson GSON = new Gson();

    private BackupContentHash() { }

    /** Hash the exact ZIP bytes for on-disk integrity; this is distinct from the data-content hash. */
    public static String archiveSha256(File backupZip) throws IOException {
        if (backupZip == null || !backupZip.isFile() || backupZip.length() == 0
                || backupZip.length() > MAX_ZIP_BYTES) {
            throw new IOException("备份 ZIP 不存在或过大");
        }
        MessageDigest digest = newDigest();
        try (InputStream input = new FileInputStream(backupZip)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_ZIP_BYTES) throw new IOException("备份 ZIP 过大");
                digest.update(buffer, 0, read);
            }
            if (total != backupZip.length()) throw new IOException("备份 ZIP 读取期间发生变化");
        }
        return hex(digest.digest());
    }

    public static String sha256(File backupZip) throws IOException {
        if (backupZip == null || !backupZip.isFile() || backupZip.length() == 0
                || backupZip.length() > MAX_ZIP_BYTES) {
            throw new IOException("备份 ZIP 不存在或过大");
        }
        try (ZipFile zip = new ZipFile(backupZip)) {
            Set<String> seen = new HashSet<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !(PREFS.equals(name) || ROOM.equals(name) || MANIFEST.equals(name))
                        || !seen.add(name)) {
                    throw new IOException("备份 ZIP 条目无效");
                }
            }
            if (!seen.contains(PREFS) || !seen.contains(MANIFEST)) {
                throw new IOException("备份 ZIP 缺少必要条目");
            }
            byte[] prefs = readBounded(zip.getInputStream(zip.getEntry(PREFS)), MAX_PREFS_BYTES);
            JsonElement parsed;
            try {
                parsed = JsonParser.parseString(new String(prefs, StandardCharsets.UTF_8));
            } catch (RuntimeException error) {
                throw new IOException("备份设置 JSON 无效", error);
            }
            if (!parsed.isJsonObject()) throw new IOException("备份设置不是 JSON 对象");
            byte[] canonicalPrefs = canonical(parsed).getBytes(StandardCharsets.UTF_8);
            MessageDigest digest = newDigest();
            digest.update(VERSION);
            digest.update(longBytes(canonicalPrefs.length));
            digest.update(canonicalPrefs);
            ZipEntry room = zip.getEntry(ROOM);
            digest.update((byte) (room == null ? 0 : 1));
            if (room != null) {
                if (room.getSize() > MAX_ROOM_BYTES) throw new IOException("备份数据库过大");
                long total = 0;
                try (InputStream input = zip.getInputStream(room)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        total += read;
                        if (total > MAX_ROOM_BYTES) throw new IOException("备份数据库过大");
                        digest.update(buffer, 0, read);
                    }
                }
                digest.update(longBytes(total));
            }
            return hex(digest.digest());
        }
    }

    private static byte[] readBounded(InputStream source, int maxBytes) throws IOException {
        try (InputStream input = source; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read > maxBytes - output.size()) throw new IOException("备份设置过大");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String canonical(JsonElement value) {
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            List<String> keys = new ArrayList<>(object.keySet());
            Collections.sort(keys);
            StringBuilder text = new StringBuilder("{");
            for (String key : keys) {
                if (text.length() > 1) text.append(',');
                text.append(GSON.toJson(key)).append(':').append(canonical(object.get(key)));
            }
            return text.append('}').toString();
        }
        if (value.isJsonArray()) {
            StringBuilder text = new StringBuilder("[");
            for (JsonElement element : value.getAsJsonArray()) {
                if (text.length() > 1) text.append(',');
                text.append(canonical(element));
            }
            return text.append(']').toString();
        }
        return value.toString();
    }

    private static MessageDigest newDigest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static byte[] longBytes(long value) {
        byte[] bytes = new byte[8];
        for (int i = 7; i >= 0; i--) {
            bytes[i] = (byte) value;
            value >>>= 8;
        }
        return bytes;
    }

    private static String hex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            chars[i * 2] = HEX[(bytes[i] >>> 4) & 15];
            chars[i * 2 + 1] = HEX[bytes[i] & 15];
        }
        return new String(chars);
    }
}
