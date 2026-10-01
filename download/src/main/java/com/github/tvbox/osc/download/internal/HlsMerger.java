package com.github.tvbox.osc.download.internal;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** 只负责按 init→分片顺序合并；不修改任务状态，失败保留原片供继续。 */
public final class HlsMerger {
    public interface Progress { void update(long bytes, int index) throws IOException; }
    private HlsMerger() {}
    public static void merge(File directory, int count, File init, File target, Set<Integer> gaps,
                             BooleanSupplier stopped, Progress progress) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        try (OutputStream out = new FileOutputStream(target)) {
            long bytes = 0;
            if (init != null) bytes += copy(init, out, buffer, stopped);
            for (int i = 0; i < count; i++) {
                if (stopped.getAsBoolean()) throw new IOException("合并已暂停");
                File segment = new File(directory, String.format(Locale.ROOT, "%05d.ts", i));
                if (!segment.isFile() || segment.length() == 0) {
                    if (gaps.contains(i)) continue;
                    throw new IOException("合并时缺少分片 " + i + "/" + count + "，请继续下载补齐");
                }
                bytes += copy(segment, out, buffer, stopped);
                if (i % 20 == 0 || i == count - 1) progress.update(bytes, i);
            }
            out.flush();
        }
    }
    private static long copy(File source, OutputStream out, byte[] buffer, BooleanSupplier stopped) throws IOException {
        long bytes = 0;
        try (FileInputStream in = new FileInputStream(source)) {
            int length;
            while ((length = in.read(buffer)) != -1) {
                if (stopped.getAsBoolean()) throw new IOException("合并已暂停");
                out.write(buffer, 0, length); bytes += length;
            }
        }
        return bytes;
    }
}
