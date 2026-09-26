package com.github.tvbox.osc.util.js;

import android.content.Context;

import androidx.annotation.Keep;

import com.whl.quickjs.wrapper.Function;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * JS 侧 localStorage 桥（util.js 的本地持久化）：键 jsRuntime_&lt;host&gt;_&lt;k&gt;。
 * <p>
 * 改存应用私有文件(js_runtime/&lt;key&gt;.txt)，不再占用配置键值存储；
 * 旧 Hawk 存量按访问惰性迁移一次。context 由 ApiConfig.setAppContext 注入。
 */
public class local {

    private static volatile Context context;

    /** ApiConfig.setAppContext 注入 */
    public static void setContext(Context c) {
        context = c == null ? null : c.getApplicationContext();
    }

    private static File fileOf(String key) {
        if (context == null) return null;
        if (key == null || key.isEmpty()) return null;
        String safe = key.replaceAll("[^A-Za-z0-9_\\-]", "_");
        return new File(new File(context.getFilesDir(), "js_runtime"), safe + ".txt");
    }

    private static String readFile(File f) {
        if (f == null || !f.exists()) return null;
        try (FileInputStream is = new FileInputStream(f)) {
            byte[] data = new byte[(int) f.length()];
            int off = 0;
            while (off < data.length) {
                int n = is.read(data, off, data.length - off);
                if (n < 0) break;
                off += n;
            }
            return new String(data, 0, off, "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeFile(File f, String content) {
        if (f == null || content == null) return;
        File dir = f.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) return;
        try (FileOutputStream os = new FileOutputStream(f)) {
            os.write(content.getBytes("UTF-8"));
            os.flush();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void deleteFile(File f) {
        if (f != null) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    @Keep
    @Function
    public void delete(String str, String str2) {
        try {
            deleteFile(fileOf("jsRuntime_" + str + "_" + str2));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Keep
    @Function
    public String get(String str, String str2) {
        try {
            String key = "jsRuntime_" + str + "_" + str2;
            String fromFile = readFile(fileOf(key));
            if (fromFile != null) return fromFile;
            return str2;
        } catch (Exception e) {
            return str2;
        }
    }

    @Keep
    @Function
    public void set(String str, String str2, String str3) {
        try {
            writeFile(fileOf("jsRuntime_" + str + "_" + str2), str3);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
