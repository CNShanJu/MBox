package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

/**
 * ua.db 解析单测(JVM,不碰 Android):格式为 {@code 条数 + 条数×4 字节偏移表 + UTF 串数据块}。
 * <p>
 * 原实现(以及本仓的等价改写)靠"跳 N×4 + 读偏移 + 再跳剩下的偏移表 + 偏移"定位条目,
 * 这段算术错了不会报错,只会偶尔返回乱码 UA —— 真机上根本发现不了,所以用合成数据把它固定住。
 */
public class UaDbParseTest {

    /** 按 ua.db 的格式合成一份数据 */
    private static byte[] buildDb(String... entries) throws Exception {
        ByteArrayOutputStream dataBlock = new ByteArrayOutputStream();
        DataOutputStream dataOut = new DataOutputStream(dataBlock);
        for (String s : entries) {
            dataOut.writeUTF(s);
        }
        dataOut.flush();
        byte[] data = dataBlock.toByteArray();

        ByteArrayOutputStream all = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(all);
        out.writeInt(entries.length);
        int offset = 0;
        for (String s : entries) {
            out.writeInt(offset);
            // 每个条目的 UTF 长度 = 2 字节长度前缀 + 修改版 UTF-8 字节数
            ByteArrayOutputStream one = new ByteArrayOutputStream();
            DataOutputStream oneOut = new DataOutputStream(one);
            oneOut.writeUTF(s);
            oneOut.flush();
            offset += one.size();
        }
        out.write(data);
        out.flush();
        return all.toByteArray();
    }

    @Test
    public void readsEveryEntryByIndex() throws Exception {
        byte[] db = buildDb(
                "Mozilla/5.0 (Windows NT 10.0) A",
                "Mozilla/5.0 (Macintosh) B",
                "Mozilla/5.0 (Linux) C",
                "Mozilla/5.0 (iPhone) D");
        assertEquals(4, UA.uaCount(db));
        assertEquals("Mozilla/5.0 (Windows NT 10.0) A", UA.uaAt(db, 0));
        assertEquals("Mozilla/5.0 (Macintosh) B", UA.uaAt(db, 1));
        assertEquals("Mozilla/5.0 (Linux) C", UA.uaAt(db, 2));
        assertEquals("Mozilla/5.0 (iPhone) D", UA.uaAt(db, 3));
    }

    @Test
    public void lastEntryIsReachable() throws Exception {
        // 边界:最后一条最容易因为偏移算术写错而读成空/越界,单独钉一遍
        byte[] db = buildDb("a", "bb", "ccc");
        assertEquals("ccc", UA.uaAt(db, 2));
    }

    @Test
    public void singleEntryDbWorks() throws Exception {
        byte[] db = buildDb("only-one");
        assertEquals(1, UA.uaCount(db));
        assertEquals("only-one", UA.uaAt(db, 0));
    }

    @Test
    public void outOfRangeIndexIsNull() throws Exception {
        byte[] db = buildDb("a", "b");
        assertNull(UA.uaAt(db, 2));
        assertNull(UA.uaAt(db, 99));
        assertNull(UA.uaAt(db, -1));
    }

    @Test
    public void brokenDbDegradesInsteadOfThrowing() {
        assertNull(UA.uaAt(null, 0));
        assertNull(UA.uaAt(new byte[0], 0));
        assertEquals(0, UA.uaCount(new byte[0]));
        assertEquals(0, UA.uaCount(null));
        // 只有条数、没有偏移表/数据块:读条目必须返回 null(由 random() 走兜底 UA),不能抛
        byte[] truncated = new byte[]{0, 0, 0, 3};
        assertEquals(3, UA.uaCount(truncated));
        assertNull(UA.uaAt(truncated, 1));
    }

    @Test
    public void randomNeverEscapesTheParsedSet() {
        // 上下文未注入时 random() 走兜底 UA(非空),这是调用方 FileUtils/UserFragment 依赖的行为
        String ua = UA.random();
        assertTrue(ua != null && ua.startsWith("Mozilla/5.0"));
    }
}
