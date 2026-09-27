package com.github.tvbox.osc.state;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 存储空间中控层的源码级绊线(纯 JVM;真实 StatFs 行为由用户人工验证)。
 *
 * <p>为什么用源码断言:"中控层"的价值全在"<b>全应用只有这一份测量与判定</b>"——
 * 多一处 {@code new StatFs} 就多一个"同一时刻给出不同答案"的机会(缓存时机不同、探测的还不是同一个卷),
 * 阈值也容易被复制成第二个字面量。这类重复不会让任何一次编译或运行失败,所以钉成断言:
 * 一旦有人又在自己模块里 {@code new StatFs} 或写死 1GB,这里就红。
 */
public class StorageSpaceContractTest {

    /** 允许出现 StatFs 的唯一实现文件(相对仓库根) */
    private static final String ONLY_STATFS_OWNER =
            "common/src/main/java/com/github/tvbox/osc/state/StorageSpace.java";

    private static File repoRoot() {
        // 单测工作目录 = :app 模块目录,仓库根在上一级
        File f = new File("..");
        return f.exists() ? f : new File(".");
    }

    /** 收集 modules 下所有 main 源码(跳过 build/ 产物) */
    private static List<File> mainSources() {
        List<File> out = new ArrayList<>();
        File root = repoRoot();
        File[] modules = root.listFiles();
        if (modules == null) return out;
        for (File m : modules) {
            File src = new File(m, "src/main/java");
            if (!src.isDirectory()) continue;
            collect(src, out);
        }
        return out;
    }

    private static void collect(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                collect(f, out);
            } else if (f.getName().endsWith(".java")) {
                out.add(f);
            }
        }
    }

    private static String rel(File f) {
        String path = f.getPath().replace('\\', '/');
        int i = path.indexOf("common/src");
        if (i >= 0) return path.substring(i);
        i = path.indexOf("app/src");
        if (i >= 0) return path.substring(i);
        i = path.indexOf("download/src");
        if (i >= 0) return path.substring(i);
        i = path.indexOf("core-storage/src");
        if (i >= 0) return path.substring(i);
        return path;
    }

    private static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void onlyStorageSpaceCreatesStatFs() throws Exception {
        List<File> sources = mainSources();
        assertTrue("没扫到源码,单测工作目录变了?", sources.size() > 50);
        List<String> offenders = new ArrayList<>();
        for (File f : sources) {
            String src = read(f);
            if (!src.contains("new StatFs(") && !src.contains("new android.os.StatFs(")) continue;
            if (ONLY_STATFS_OWNER.equals(rel(f))) continue;
            offenders.add(rel(f));
        }
        assertEquals("磁盘测量只能有中控层 StorageSpace 一处实现(别处请调 StorageSpace.freeBytes),"
                + "违反:" + offenders, 0, offenders.size());
    }

    @Test
    public void storageSpaceDelegatesEveryDecisionToTheSharedPolicy() throws Exception {
        String src = read(new File(repoRoot(), ONLY_STATFS_OWNER));
        assertTrue("中控层必须把判定交给 StorageGuardPolicy(不能自己写阈值)",
                src.contains("StorageGuardPolicy.isLow(") && src.contains("StorageGuardPolicy.canWrite("));
        assertTrue("中控层不许出现第二个 1GB 字面量",
                !src.contains("1024L * 1024 * 1024") && !src.contains("1024 * 1024 * 1024"));
    }

    @Test
    public void watchdogReadsMeasurementFromTheCentralLayer() throws Exception {
        File watchdog = new File(repoRoot(),
                "download/src/main/java/com/github/tvbox/osc/download/internal/StorageWatchdog.java");
        String src = read(watchdog);
        assertTrue("看门狗的空间测量必须问中控层(不再自己 new StatFs)",
                src.contains("StorageSpace.freeBytes("));
        assertTrue("看门狗的阈值判定仍只能来自 StorageGuardPolicy",
                src.contains("StorageGuardPolicy.isLow("));
    }
}
