package com.github.tvbox.osc.download.internal;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 重封装样本缓冲的"直接缓冲"契约绊线(纯 JVM 源码级断言)。
 *
 * <p>为什么用源码断言而不是行为断言:{@code MediaExtractor.readSampleData} 与
 * {@code MediaMuxer.writeSampleData} 都是 native 实现,经 {@code GetDirectBufferAddress} 取数据地址,
 * 传堆缓冲({@code ByteBuffer.allocate})会在 native 侧抛 {@code IllegalArgumentException};
 * 而重封装外层是 {@code catch (Throwable) → Log.w("重封装失败") → return false → 回退 .ts},
 * 于是"HLS 成品永远变不成 MP4"这种故障**静默**发生(3.5.7 引入重封装后一直如此,直到本轮修复)。
 * 本机跑不了 MediaExtractor,所以这里守住最容易回退的一行:缓冲的分配方式。
 *
 * <p>什么时候可以删掉本测试:真机上能跑一次重封装集成测试(或改用 Media3 Transformer 之类
 * 由框架自己管缓冲的 API)时。
 */
public class RemuxBufferContractTest {

    private static File executorSource() {
        // 单测工作目录 = :app 模块目录(Gradle 默认),故实现文件在 ../download/...
        String[] candidates = {
                "../download/src/main/java/com/github/tvbox/osc/download/internal/MediaRemuxer.java",
                "download/src/main/java/com/github/tvbox/osc/download/internal/MediaRemuxer.java",
        };
        for (String c : candidates) {
            File f = new File(c);
            if (f.exists()) return f;
        }
        return new File(candidates[0]);
    }

    @Test
    public void remuxSampleBufferMustBeDirect() throws Exception {
        File f = executorSource();
        assertTrue("找不到被测源文件(单测工作目录变了?): " + f.getAbsolutePath(), f.exists());
        String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        assertTrue("重封装的样本缓冲必须用 ByteBuffer.allocateDirect(...)", src.contains("ByteBuffer.allocateDirect("));
        assertFalse("重封装的样本缓冲不能再退回堆缓冲 ByteBuffer.allocate(...)"
                + "(native 侧取不到地址 → 重封装静默失败 → 成品回退 .ts)", src.contains("ByteBuffer.allocate("));
        // 同一块缓冲会被反复写入:每样本前必须复位,否则第二次读会从上次的 position/limit 继续
        assertTrue("每次读样本前要 buffer.clear()", src.contains("buffer.clear();"));
    }
}
