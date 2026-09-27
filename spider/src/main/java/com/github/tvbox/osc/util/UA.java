package com.github.tvbox.osc.util;

import android.content.Context;

import java.io.DataInputStream;
import java.io.InputStream;
import java.util.Random;

public class UA {

    /** 注入的 application context（独立模块 :spider，由 ApiConfig.setAppContext 同步设置；ua.db 在本模块 assets） */
    private static volatile Context context;

    public static void setContext(Context c) {
        context = c == null ? null : c.getApplicationContext();
    }

    private static String[] uas = new String[]{
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/93.0.4557.4 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.114 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/89.0.4389.114 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.127 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.109 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/93.0.4557.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_14_5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.114 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.91 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_13_6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/93.0.4556.0 Safari/537.36",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/93.0.4530.0 Safari/537.36",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.114 Safari/537.36",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.6261.94 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/93.0.4537.0 Safari/537.36 Edg/93.0.926.0",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36 Edg/91.0.864.64",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 Edg/126.0.2592.61",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.106 Safari/537.36 Edg/91.0.864.53",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:115.0) Gecko/20100101 Firefox/115.0",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:127.0) Gecko/20100101 Firefox/127.0",
            "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.0 Safari/605.1.15",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/14.1.1 Safari/605.1.15",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 13_5) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Safari/605.1.15",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_5) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 15_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.0 Mobile/15E148 Safari/604.1",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 14_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/14.1.1 Mobile/15E148 Safari/604.1",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 14_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/14.0.1 Mobile/15E148 Safari/604.1",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.5 Mobile/15E148 Safari/604.1",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1",
            "Mozilla/5.0 (Linux; Android 11; Pixel 3 XL) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 11; M2007J3SP) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/92.0.4515.20 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 10; Redmi 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 12; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/117.0.5938.140 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.210 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.71 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 11; vivo 1933 Build/RP1A.200720.012; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/91.0.4472.120 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 13; Pixel 7 Build/TQ3A.230805.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/120.0.6099.210 Mobile Safari/537.36",
            "Mozilla/5.0 (iPad; CPU OS 14_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/14.1.1 Mobile/15E148 Safari/604.1",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 14_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/91.0.4472.80 Mobile/15E148 Safari/604.1",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.114 Safari/537.36 OPR/77.0.4054.172",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.106 YaBrowser/21.6.0.616 Yowser/2.5 Safari/537.36",
            "Mozilla/5.0 (X11; CrOS x86_64 13982.39.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/92.0.4515.82 Safari/537.36",
            "Mozilla/5.0 (Linux; Android 5.0.2; SM-T700) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/93.0.4551.2 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.77 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/14.1 Safari/605.1.15",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 14_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) GSA/166.0.381336632 Mobile/15E148 Safari/604.1",
    };

    public static String randomOne() {
        int num = uas.length;
        int key = (int)(Math.random()*num);
        return uas[key];
    }

    /** ua.db 解析失败/上下文未注入时的兜底 UA(与原来 return 的那条一致) */
    private static final String FALLBACK_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.114 Safari/537.36";

    /**
     * assets/ua.db 的全量字节(约 670KB),首次用到时读一次。
     * <p>
     * 原来每次 {@link #random()} 都 {@code assets.open + 读整个文件}:
     * ①两个流(AssetInputStream 与 DataInputStream)从不关闭,连 catch 分支也不关 —— 调用点在
     * 豆瓣热门的循环里(每条视频一个 UA),一个列表就泄掉一批 fd,几百个源规模直接
     * "Too many open files";②每次请求重读 670KB 纯属浪费。缓存 + try-with-resources 一起解决。
     */
    private static volatile byte[] uaDb;

    public static String random() {
        byte[] db = uaDb();
        if (db == null) return FALLBACK_UA;
        int len = uaCount(db);
        if (len <= 0) return FALLBACK_UA;
        String ua = uaAt(db, new Random().nextInt(len));
        return ua == null ? FALLBACK_UA : ua;
    }

    /**
     * ua.db 里的 UA 条数;文件格式坏/长度为 0 时返回 0。
     * <p>
     * 解析拆成 {@code uaCount/uaAt} 两个纯函数是为了可 JVM 单测(见 UaDbParseTest):
     * 这段"跳偏移表 → 跳数据块 → readUTF"的算术很绕,而且错了只会表现为"偶尔取到乱码 UA",
     * 在真机上几乎不可能定位。
     */
    static int uaCount(byte[] db) {
        if (db == null || db.length < 4) return 0;
        try (DataInputStream dis = new DataInputStream(new java.io.ByteArrayInputStream(db))) {
            int len = dis.readInt();
            return Math.max(len, 0);
        } catch (Throwable e) {
            return 0;
        }
    }

    /** 取第 index 条 UA(越界/格式不符返回 null);解析口径与原实现一致 */
    static String uaAt(byte[] db, int index) {
        if (db == null || index < 0) return null;
        try (DataInputStream dis = new DataInputStream(new java.io.ByteArrayInputStream(db))) {
            int len = dis.readInt();
            if (index >= len) return null;
            dis.skipBytes(index * 4);
            int offset = dis.readInt();
            dis.skipBytes((len - 1 - index) * 4 + offset);
            return dis.readUTF();
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return null;
    }

    /** 读一次 ua.db 并缓存;context 未注入/文件缺失/读取失败都返回 null(由调用方走兜底) */
    private static byte[] uaDb() {
        byte[] cached = uaDb;
        if (cached != null) return cached;
        Context c = context;
        if (c == null) return null;
        synchronized (UA.class) {
            if (uaDb != null) return uaDb;
            try (InputStream in = c.getAssets().open("ua.db")) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(in.available() > 0 ? in.available() : 8192);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
                uaDb = bos.toByteArray();
            } catch (Throwable e) {
                e.printStackTrace();
                return null;
            }
            return uaDb;
        }
    }
}
