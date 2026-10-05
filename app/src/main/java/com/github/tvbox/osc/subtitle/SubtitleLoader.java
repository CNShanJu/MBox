/*
 *                       Copyright (C) of Avery
 *
 *                              _ooOoo_
 *                             o8888888o
 *                             88" . "88
 *                             (| -_- |)
 *                             O\  =  /O
 *                          ____/`- -'\____
 *                        .'  \\|     |//  `.
 *                       /  \\|||  :  |||//  \
 *                      /  _||||| -:- |||||-  \
 *                      |   | \\\  -  /// |   |
 *                      | \_|  ''\- -/''  |   |
 *                      \  .-\__  `-`  ___/-. /
 *                    ___`. .' /- -.- -\  `. . __
 *                 ."" '<  `.___\_<|>_/___.'  >'"".
 *                | | :  `- \`.;`\ _ /`;.`/ - ` : | |
 *                \  \ `-.   \_ __\ /__ _/   .-` /  /
 *           ======`-.____`-.___\_____/___.-`____.-'======
 *                              `=- -='
 *           ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
 *              Buddha bless, there will never be bug!!!
 */

package com.github.tvbox.osc.subtitle;

import android.text.TextUtils;
import android.util.Log;

import com.github.tvbox.osc.subtitle.exception.FatalParsingException;
import com.github.tvbox.osc.subtitle.format.FormatASS;
import com.github.tvbox.osc.subtitle.format.FormatSCC;
import com.github.tvbox.osc.subtitle.format.FormatSRT;
import com.github.tvbox.osc.subtitle.format.FormatSTL;
import com.github.tvbox.osc.subtitle.format.FormatTTML;
import com.github.tvbox.osc.subtitle.format.TimedTextFileFormat;
import com.github.tvbox.osc.subtitle.model.TimedTextObject;
import com.github.tvbox.osc.subtitle.runtime.AppTaskExecutor;
import com.github.tvbox.osc.util.CharsetUtils;
import com.github.tvbox.osc.util.HttpClient;
import com.github.tvbox.osc.util.UnicodeReader;

import org.apache.commons.io.input.ReaderInputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * @author AveryZhong.
 */

public class SubtitleLoader {
    private static final String TAG = SubtitleLoader.class.getSimpleName();
    private static final int MAX_SUBTITLE_BYTES = 8 * 1024 * 1024;

    private SubtitleLoader() {
        throw new AssertionError("No instance for you.");
    }

    public static void loadSubtitle(final String path, final Callback callback) {
        if (TextUtils.isEmpty(path)) {
            return;
        }
        if (path.startsWith("http://")
                || path.startsWith("https://")) {
            loadFromRemoteAsync(path, callback);
        } else {
            loadFromLocalAsync(path, callback);
        }
    }

    private static void loadFromRemoteAsync(final String remoteSubtitlePath,
                                            final Callback callback) {
        AppTaskExecutor.deskIO().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final SubtitleLoadSuccessResult subtitleLoadSuccessResult = loadFromRemote(remoteSubtitlePath);
                    if (callback != null) {
                        AppTaskExecutor.mainThread().execute(new Runnable() {
                            @Override
                            public void run() {
                                callback.onSuccess(subtitleLoadSuccessResult);
                            }
                        });
                    }

                } catch (final Exception e) {
                    e.printStackTrace();
                    if (callback != null) {
                        AppTaskExecutor.mainThread().execute(new Runnable() {
                            @Override
                            public void run() {
                                callback.onError(e);
                            }
                        });
                    }

                }
            }
        });
    }

    private static void loadFromLocalAsync(final String localSubtitlePath,
                                           final Callback callback) {
        AppTaskExecutor.deskIO().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final SubtitleLoadSuccessResult subtitleLoadSuccessResult = loadFromLocal(localSubtitlePath);
                    if (callback != null) {
                        AppTaskExecutor.mainThread().execute(new Runnable() {
                            @Override
                            public void run() {
                                callback.onSuccess(subtitleLoadSuccessResult);
                            }
                        });
                    }

                } catch (final Exception e) {
                    e.printStackTrace();
                    if (callback != null) {
                        AppTaskExecutor.mainThread().execute(new Runnable() {
                            @Override
                            public void run() {
                                callback.onError(e);
                            }
                        });
                    }

                }
            }
        });
    }

    public SubtitleLoadSuccessResult loadSubtitle(String path) {
        if (TextUtils.isEmpty(path)) {
            return null;
        }
        try {
            if (path.startsWith("http://")
                    || path.startsWith("https://")) {
                return loadFromRemote(path);
            } else {
                return loadFromLocal(path);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private static SubtitleLoadSuccessResult loadFromRemote(final String remoteSubtitlePath)
            throws IOException, FatalParsingException, Exception {
        Log.d(TAG, "parseRemote: loading remote subtitle");
        String referer = "";
        if (remoteSubtitlePath.contains("alicloud") || remoteSubtitlePath.contains("aliyundrive")) {
            referer = "https://www.aliyundrive.com/";
        } else if (remoteSubtitlePath.contains("assrt.net")) {
            referer = "https://secure.assrt.net/";
        }
        String ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/94.0.4606.54 Safari/537.36";
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", referer);
        headers.put("User-Agent", ua);
        Response response = HttpClient.getResponseSync(remoteSubtitlePath, headers);
        if (response == null) throw new IOException("Subtitle response is empty");
        try {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                throw new IOException("Subtitle request failed: HTTP " + response.code());
            }
            byte[] bytes = readLimited(body.byteStream(), body.contentLength(), MAX_SUBTITLE_BYTES);
            // 字符集探测统一走 CharsetUtils(UniversalDetector + 中文常用字回退;探测失败不再 NPE)
            String content = new String(bytes, CharsetUtils.detect(bytes));
            InputStream is = new ByteArrayInputStream(content.getBytes());
            String filename = SubtitleFilePolicy.suggestedName(
                    response.header("content-disposition", ""), remoteSubtitlePath);
            SubtitleLoadSuccessResult subtitleLoadSuccessResult = new SubtitleLoadSuccessResult();
            subtitleLoadSuccessResult.timedTextObject = loadAndParse(is, filename);
            subtitleLoadSuccessResult.fileName = filename;
            subtitleLoadSuccessResult.content = content;
            subtitleLoadSuccessResult.subtitlePath = remoteSubtitlePath;
            return subtitleLoadSuccessResult;
        } finally {
            response.close();
        }
    }

    private static SubtitleLoadSuccessResult loadFromLocal(final String localSubtitlePath)
            throws IOException, FatalParsingException {
        Log.d(TAG, "parseLocal: localSubtitlePath = " + localSubtitlePath);
        File file = new File(localSubtitlePath);
        if (!file.exists()) {
            Log.d(TAG, "parseLocal: localSubtitlePath = " + localSubtitlePath + " file not exsits");
            return null;
        }
        byte[] bytes;
        try (InputStream input = new FileInputStream(file)) {
            bytes = readLimited(input, file.length(), MAX_SUBTITLE_BYTES);
        }
        // 字符集探测统一走 CharsetUtils(UniversalDetector + 中文常用字回退;探测失败不再 NPE)
        String content = new String(bytes, CharsetUtils.detect(bytes));
        InputStream is = new ByteArrayInputStream(content.getBytes());
        String filePath = file.getPath();
        SubtitleLoadSuccessResult subtitleLoadSuccessResult = new SubtitleLoadSuccessResult();
        subtitleLoadSuccessResult.timedTextObject = loadAndParse(is, filePath);
        String fileName = filePath.substring(filePath.lastIndexOf("/") + 1);
        subtitleLoadSuccessResult.fileName = fileName;
        subtitleLoadSuccessResult.subtitlePath = localSubtitlePath;
        return subtitleLoadSuccessResult;
    }

    private static TimedTextObject loadAndParse(final InputStream is, final String filePath)
            throws IOException, FatalParsingException {
        String fileName = filePath.substring(filePath.lastIndexOf("/") + 1);
        String ext = "";
        if (fileName.lastIndexOf(".") > 0) {
            ext = fileName.substring(fileName.lastIndexOf("."));
        }
        Log.d(TAG, "parse: name = " + fileName + ", ext = " + ext);
        Reader reader = new UnicodeReader(is); //处理有BOM头的utf8
        InputStream newInputStream = new ReaderInputStream(reader, Charset.defaultCharset());
        // 先把内容整份读进内存:每种候选格式都喂**独立的**流。
        // 原实现让 4 个候选共用同一个流,第一个解析器读空/关掉之后,后面的候选只能拿到空内容,
        // 于是"后缀写错的文件"(如 .txt/.srt 里其实是 ASS)永远解析不出字幕。
        byte[] bytes = readAll(newInputStream);
        newInputStream.close();
        if (bytes.length == 0) {
            Log.d(TAG, "parse: empty subtitle stream, name = " + fileName);
            return null;
        }
        // 后缀优先;解析不出字幕(或该格式解析异常)时再按内容逐个试其余格式。
        // 这样既保留"srt/ass/stl/ttml 各走自己解析器"的确定性,又给错后缀/怪后缀兜底。
        TimedTextObject fallback = null;
        for (TimedTextFileFormat format : candidateFormats(ext)) {
            try {
                TimedTextObject tto = format.parseFile(fileName, new ByteArrayInputStream(bytes));
                if (tto != null && tto.captions != null && !tto.captions.isEmpty()) {
                    return tto;
                }
                if (fallback == null) fallback = tto;
            } catch (Throwable th) {
                Log.d(TAG, "parse with " + format.getClass().getSimpleName() + " failed: " + th.getMessage());
            }
        }
        return fallback;
    }

    /** 按后缀挑首选解析器,再补上其余格式作为"内容兜底"(顺序稳定,便于复现) */
    private static TimedTextFileFormat[] candidateFormats(String ext) {
        TimedTextFileFormat[] all = {new FormatSRT(), new FormatASS(), new FormatTTML(), new FormatSTL(), new FormatSCC()};
        TimedTextFileFormat preferred = null;
        if (".srt".equalsIgnoreCase(ext) || ".sub".equalsIgnoreCase(ext)) {
            preferred = all[0];
        } else if (".ass".equalsIgnoreCase(ext) || ".ssa".equalsIgnoreCase(ext)) {
            preferred = all[1];
        } else if (".ttml".equalsIgnoreCase(ext) || ".xml".equalsIgnoreCase(ext)) {
            preferred = all[2];
        } else if (".stl".equalsIgnoreCase(ext)) {
            preferred = all[3];
        } else if (".scc".equalsIgnoreCase(ext)) {
            preferred = all[4];
        }
        if (preferred == null) {
            return all;
        }
        TimedTextFileFormat[] order = new TimedTextFileFormat[all.length];
        order[0] = preferred;
        int i = 1;
        for (TimedTextFileFormat f : all) {
            if (f != preferred) order[i++] = f;
        }
        return order;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    static byte[] readLimited(InputStream in, long declaredLength, int maxBytes) throws IOException {
        BufferedInputStream input = in instanceof BufferedInputStream
                ? (BufferedInputStream) in : new BufferedInputStream(in);
        // Identify bitmap subtitles before the size check or any character decoding.
        SubtitleInputPolicy.requireSupportedContent(input);
        if (declaredLength > maxBytes) throw new IOException("Subtitle exceeds size limit");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count > maxBytes - out.size()) throw new IOException("Subtitle exceeds size limit");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }

    public interface Callback {
        void onSuccess(SubtitleLoadSuccessResult SubtitleLoadSuccessResult);

        void onError(Exception exception);
    }
}
