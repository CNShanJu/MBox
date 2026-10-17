package com.github.catvod.crawler;

import java.io.BufferedReader;
import java.io.FileReader;

/**
 * 进程真实物理内存(VmRSS)读取。
 * <p>
 * Java 的 {@link Runtime} 与 {@code Debug.getNativeHeapAllocatedSize()} 都统计不到
 * WebView(chromium) 用 mmap 申请的大块内存，故读 {@code /proc/self/status} 的 VmRSS
 * 作为权威口径。目前用于 Csp 主 jar 加载前的内存闸门(见 {@link JarLoader})。
 */
public final class MemProbe {

    private MemProbe() {
    }

    /** 进程实际物理内存(RSS, MB)。读不到返回 -1。 */
    public static long vmRssMb() {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/self/status"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("VmRSS:")) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 2) return Long.parseLong(parts[1]) / 1024;
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }
}
