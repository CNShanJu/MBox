package com.github.tvbox.osc.download.internal;

import android.media.MediaExtractor;
import android.media.MediaMuxer;
import android.util.Log;

import com.github.tvbox.osc.bean.DownloadTask;
import com.github.tvbox.osc.util.DownloadHeaders;
import com.github.tvbox.osc.util.HlsMediaPlaylist;
import com.github.tvbox.osc.util.HlsSizeEstimator;
import com.github.tvbox.osc.util.HttpClient;
import com.github.tvbox.osc.util.SegmentUnwrapper;
import com.github.tvbox.osc.util.TsProbe;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Request;
import okhttp3.Response;

/**
 * 下载执行门面：分发到直链/HLS/合并/封装组件，复用请求客户端与大小探测。
 * 各组件属于 download.internal，UI 通过 DownloadFacade 协作。
 */
public class DownloadExecutor {

    private final DownloadManager dm;
    private final DirectDownloader direct;
    private final HlsDownloader hls;
    private final MediaRemuxer remuxer;

    DownloadExecutor(DownloadManager dm) {
        this.dm = dm;
        direct = new DirectDownloader(dm, this);
        hls = new HlsDownloader(dm, this);
        remuxer = new MediaRemuxer(dm, this);
    }

    void processTask(DownloadTask t) throws IOException {
        if (t.url != null && t.url.toLowerCase().contains(".m3u8")) {
            downloadHls(t);
        } else {
            downloadDirect(t);
        }
    }

    // ------------------------------------------------------------------
    // 直链下载(断点续传)
    // ------------------------------------------------------------------

    /** 直链下载算法入口（4.6 任务对象化: 由 NormalFileDownloadTask.doRun 委托） */
    public void downloadDirect(DownloadTask t) throws IOException { direct.download(t); }
    public void downloadHls(DownloadTask t) throws IOException { hls.download(t); }
    static final int SEG_URL_LOG_MAX = HlsDownloader.SEG_URL_LOG_MAX;
    static String segUrlForLog(String url) { return HlsDownloader.segUrlForLog(url); }
    static String joinUrl(String scheme, String authority, String path) { return HlsDownloader.joinUrl(scheme, authority, path); }
    private static int countSegmentLines(String text) { return HlsDownloader.countSegmentLines(text); }
    private static String resolveUrl(String original, String base, String segment) { return HlsDownloader.resolveUrl(original, base, segment); }
    Map<String, String> baseHeaders(DownloadTask t) {
        synchronized (t) {
            Map<String, String> result = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            result.putAll(DownloadHeaders.merge(java.util.Collections.singletonMap("User-Agent", "MBox/Media3"), t.headers));
            return result;
        }
    }
    /** 任务的分段下载目录:优先任务自己的 tmpDir,否则按 文件目录/tmp/<任务id> 兜底(保证唯一,多任务不共用) */
    File segmentsDirOf(DownloadTask t) {
        if (t.tmpDir != null && !t.tmpDir.isEmpty()) {
            return new File(t.tmpDir);
        }
        String identity = t.episodeId != null ? t.episodeId : t.id;
        File directory = new File(FileCleaner.getPrivateTmpRoot(), "tmp" + File.separator
                + com.github.tvbox.osc.util.MD5.encode(identity == null ? "unknown" : identity));
        t.tmpDir = directory.getAbsolutePath();
        return directory;
    }

    /**
     * 删除本任务的分段目录 tmp/<任务id>(只删本任务,不删父级 tmp,避免频繁新建/删除)
     */
    void deleteSegmentsDir(DownloadTask t) {
        FileCleaner.deleteRecursive(segmentsDirOf(t));
    }

    /** 用下载专用客户端(更长超时)发起同步请求,调用方负责关闭 Response */
    Response getDownloadResponse(String url, Map<String, String> headers) throws IOException {
        try {
            Request.Builder builder = new Request.Builder().url(HttpClient.normalizeUrl(url));
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        builder.header(entry.getKey(), entry.getValue());
                    }
                }
            }
            builder.header("Accept-Encoding", "identity");
            return dm.downloadClient().newCall(builder.build()).execute();
        } catch (IOException e) {
            throw e;
        } catch (Throwable th) {
            throw new IOException("request build failed: " + th.getMessage(), th);
        }
    }

    // ------------------------------------------------------------------
    // 大小预检(入队异步 / 启动前磁盘预检共用;单飞去重,失败保持 0 不阻塞下载)
    // ------------------------------------------------------------------

    /** 任务大小是否已确定(直链精确 totalBytes 或 m3u8 估算 estimatedBytes) */
    private static boolean sizeKnown(DownloadTask t) {
        return t.totalBytes > 0 || t.estimatedBytes > 0;
    }

    /** 非阻塞预检(异步探测线程调用):已探测或在途则跳过 */
    void probeSize(DownloadTask t) {
        probeSize(t, false);
    }

    /** 阻塞预检(任务启动前磁盘预检调用):若异步探测在途则等待其完成,避免重复请求 */
    void probeSizeBlocking(DownloadTask t) {
        probeSize(t, true);
    }

    private void probeSize(final DownloadTask t, boolean waitIfBusy) {
        if (t == null || t.url == null)
            return;
        boolean mine = false;
        long epoch;
        DownloadTask request = new DownloadTask();
        synchronized (t) {
            epoch = t.requestEpoch;
            request.url = t.url;
            request.headers = DownloadHeaders.merge(t.headers);
            request.id = t.id;
            request.episodeId = t.episodeId;
            request.fileName = t.fileName;
            if (sizeKnown(t))
                return; // 已有大小(持久化/本次已探测到):不再探测
            if (t.probeDone)
                return; // 本会话已尝试过(服务器不返回大小等):不再重复探测
            if (t.probing) {
                if (!waitIfBusy)
                    return;
            } else {
                t.probing = true;
                mine = true;
            }
        }
        if (!mine) {
            // 探测在途:等待其完成(至多 5s),完成后字段已更新
            long end = System.currentTimeMillis() + 5000;
            synchronized (t) {
                while (t.probing && System.currentTimeMillis() < end) {
                    try {
                        t.wait(100);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            return;
        }
        try {
            boolean hlsUrl = request.url.toLowerCase().contains(".m3u8");
            long bytes = hlsUrl ? estimateHlsBytes(request) : probeDirectBytes(request);
            synchronized (t) {
                if (epoch != t.requestEpoch) return;
                if (hlsUrl) t.estimatedBytes = bytes;
                else if (bytes > 0) t.totalBytes = bytes;
            }
        } catch (Throwable th) {
            Log.i("TVBox-Download", "大小探测失败: " + (t.fileName == null ? "?" : t.fileName) + " "
                    + (th.getMessage() == null ? th.toString() : th.getMessage()));
        } finally {
            synchronized (t) {
                t.probing = false;
                t.probeDone = epoch == t.requestEpoch;
                t.notifyAll();
            }
        }
    }

    /**
     * 直链精确大小:Range bytes=0-0 探测(206 时取 Content-Range 总长,200 时取 Content-Length);失败返回
     * 0
     */
    private long probeDirectBytes(DownloadTask t) {
        try {
            Map<String, String> headers = baseHeaders(t);
            headers.put("Range", "bytes=0-0");
            Response resp = getDownloadResponse(t.url, headers);
            try {
                if (!resp.isSuccessful())
                    return 0;
                String cr = resp.header("Content-Range"); // 206: bytes 0-0/123456
                if (cr != null) {
                    int slash = cr.lastIndexOf('/');
                    if (slash >= 0) {
                        try {
                            return Long.parseLong(cr.substring(slash + 1).trim());
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                String cl = resp.header("Content-Length");
                if (cl != null) {
                    try {
                        return Long.parseLong(cl.trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
                return 0;
            } finally {
                resp.close();
            }
        } catch (Throwable th) {
            return 0;
        }
    }

    /** 大小抽样探测的样本片数上限(为什么是这个量级见 :common {@link HlsSizeEstimator}) */
    private static final int SIZE_SAMPLE_COUNT = HlsSizeEstimator.DEFAULT_SAMPLE_COUNT;
    /** 抽样探测的整体时间预算(ms):用尽就用手上已有的样本推算,绝不把任务启动卡在预检上 */
    private static final long SIZE_SAMPLE_BUDGET_MS = 20000L;
    /** 单个抽样请求的硬超时(秒,含连接):CDN 挂住时不能让十几个样本串成十几分钟 */
    private static final long SIZE_SAMPLE_CALL_TIMEOUT_SEC = 8L;

    /**
     * m3u8 集大小估算:<b>抽样探测 + 推算</b> —— 分层抽取若干分片拿它们的真实字节数,用平均片大小 × 片数。
     *
     * <p>为什么不再只用"码率 × 时长":主播放列表的 {@code BANDWIDTH} 是声明值(写峰值/写平均都有),
     * 清单里没有它时旧口径直接退化成"片数 × 2MB"这类拍脑袋估值 —— 都是成倍误差,而下载前的磁盘预检
     * 与下载页"约大小"都吃这个数。抽样拿的是**服务器实际给出的长度**,不受声明值影响。
     *
     * <p>三级口径(依次退化,任何一级失败都往下走,最终失败返回 0 = 不阻塞下载):
     * <ol>
     *   <li><b>抽样探测</b>:见 {@link #sampleSegmentSizes};字节范围清单({@code #EXT-X-BYTERANGE})
     *       直接读范围长度求和,连请求都不用发;</li>
     *   <li>{@code BANDWIDTH × 总时长}(原口径,EXTINF 累计);</li>
     *   <li>分片数 × 2MB(最后兜底)。</li>
     * </ol>
     */
    private long estimateHlsBytes(DownloadTask t) {
        try {
            String url = t.url;
            String text = fetchText(url, t);
            if (text == null)
                return 0;
            long bandwidth = 0;
            if (text.contains("#EXT-X-STREAM-INF")) {
                // 主播放列表(多码率):与 fetchPlaylist 一致选第一个变体,并读取其 BANDWIDTH;
                // 抽样要按**变体清单**里的分片地址去探(主列表里没有分片)
                String base = url.substring(0, url.lastIndexOf('/') + 1);
                boolean pending = false;
                for (String raw : text.split("\n")) {
                    String line = raw.trim();
                    if (line.startsWith("#EXT-X-STREAM-INF")) {
                        Matcher m = BANDWIDTH_PATTERN.matcher(line);
                        if (m.find()) {
                            try {
                                bandwidth = Long.parseLong(m.group(1));
                            } catch (NumberFormatException ignored) {
                            }
                        }
                        pending = true;
                    } else if (pending && !line.isEmpty() && !line.startsWith("#")) {
                        // 变体清单地址:分片相对地址按它解析(与 fetchPlaylist 选第一个变体的口径一致)
                        url = resolveUrl(url, base, line);
                        String variant = fetchText(url, t);
                        if (variant == null)
                            return 0;
                        text = variant;
                        pending = false;
                        break;
                    }
                }
                if (pending)
                    return 0; // 主列表无有效变体,无法估算
            }
            // 用与下载同一份解析器把清单读成"分片表"(真实地址 + 字节范围):
            // 自己按文本行数数会在相对地址/字节范围/HTML 包裹这些形态上数错
            HlsMediaPlaylist.Result parsed = HlsMediaPlaylist.parse(url, text);
            int segs = parsed.ok() ? parsed.segments.size() : 0;
            if (segs <= 0)
                segs = countSegmentLines(text); // 解析器不认的清单:按行数近似(仍好过 0)
            if (segs <= 0)
                return 0;
            // ① 抽样探测 + 推算(首选)
            if (parsed.ok() && !parsed.segments.isEmpty()) {
                long[] samples = sampleSegmentSizes(parsed, t);
                int enough = Math.min(HlsSizeEstimator.MIN_USABLE_SAMPLES, segs);
                if (samples.length >= enough) {
                    long est = HlsSizeEstimator.estimateTotalBytes(samples, segs);
                    if (est > 0) {
                        Log.i("TVBox-Download", "大小抽样探测: " + samples.length + "/" + segs + " 片样本,均值 "
                                + (est / segs) + "B/片 → 整集约 " + formatSize(est)
                                + (t.fileName == null ? "" : " " + t.fileName));
                        return est;
                    }
                }
            }
            // ② 退化:码率 × 时长(EXTINF 累计;没有则按每片 6s 兜底)
            double durationSec = 0;
            Matcher dur = EXTINF_PATTERN.matcher(text);
            while (dur.find()) {
                try {
                    durationSec += Double.parseDouble(dur.group(1));
                } catch (NumberFormatException ignored) {
                }
            }
            if (bandwidth > 0) {
                double sec = durationSec > 0 ? durationSec : segs * 6.0;
                long byBandwidth = Math.max(1L, (long) (sec * bandwidth / 8.0));
                Log.i("TVBox-Download", "大小估算退化到码率口径: " + bandwidth + "bps × " + (long) sec + "s → "
                        + formatSize(byBandwidth));
                return byBandwidth;
            }
            // ③ 最后兜底:分片数 × 2MB
            Log.i("TVBox-Download", "大小估算退化到兜底口径: " + segs + " 片 × 2MB");
            return segs * 2L * 1024 * 1024;
        } catch (Throwable th) {
            return 0;
        }
    }

    /** {@code #EXTINF:<秒>} 取值(码率口径退化时才用);静态复用,不在每次估算里重建 */
    private static final Pattern EXTINF_PATTERN = Pattern.compile("#EXTINF:\\s*([0-9]+(?:\\.[0-9]+)?)");

    /** 主播放列表的 {@code BANDWIDTH=<bps>}(码率口径退化时才用);静态复用 */
    private static final Pattern BANDWIDTH_PATTERN = Pattern.compile("BANDWIDTH=(\\d+)");

    /**
     * 抽样探测:分层抽取 {@link #SIZE_SAMPLE_COUNT} 片,逐片拿真实字节数(见 {@link #probeSegmentBytes}),
     * 返回<b>有效样本</b>(>0)组成的数组 —— 探测失败/长度未知的样本被丢弃,个数可能少于抽取数。
     *
     * <p>抽哪几片由 {@link HlsSizeEstimator#sampleIndices} 决定(分层随机,铺满整份清单);
     * 整体有时间预算({@link #SIZE_SAMPLE_BUDGET_MS}):CDN 慢/HEAD 挂住时到手多少样本就用多少,
     * 绝不把"开始下载"卡在预检上。
     */
    private long[] sampleSegmentSizes(HlsMediaPlaylist.Result parsed, DownloadTask t) {
        List<HlsMediaPlaylist.Segment> all = parsed.segments;
        int[] idx = HlsSizeEstimator.sampleIndices(all.size(), SIZE_SAMPLE_COUNT, new java.util.Random());
        long[] samples = new long[idx.length];
        int ok = 0;
        long deadline = System.currentTimeMillis() + SIZE_SAMPLE_BUDGET_MS;
        for (int k = 0; k < idx.length; k++) {
            HlsMediaPlaylist.Segment s = all.get(idx[k]);
            // 字节范围清单:长度清单里就写着,精确值且零请求
            long size = s.range != null ? s.range.length : probeSegmentBytes(s.url, t);
            if (size > 0)
                samples[ok++] = size;
            if (System.currentTimeMillis() >= deadline)
                break; // 预算用尽:拿已有的样本推算
        }
        return ok == samples.length ? samples : java.util.Arrays.copyOf(samples, ok);
    }

    /**
     * 单片字节数:先 HEAD 取 {@code Content-Length};服务器不认 HEAD(405/501 等)或没给长度时,
     * 退一步用 {@code Range: bytes=0-0} 读 {@code Content-Range} 的总长(200 忽略 Range 时用 Content-Length)。
     * 都拿不到返回 0(该样本作废,不影响其它样本)。
     */
    private long probeSegmentBytes(String url, DownloadTask t) {
        Map<String, String> headers = baseHeaders(t);
        long byHead = probeHeadBytes(url, headers);
        return byHead > 0 ? byHead : probeRangeBytes(url, headers);
    }

    /** HEAD 探测文件总长;失败/无长度返回 0 */
    private long probeHeadBytes(String url, Map<String, String> headers) {
        Response resp = null;
        try {
            Request.Builder builder = new Request.Builder().url(HttpClient.normalizeUrl(url)).head();
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) builder.header(e.getKey(), e.getValue());
                }
            }
            resp = sizeProbeClient().newCall(builder.build()).execute();
            if (!resp.isSuccessful())
                return 0;
            return parseLongQuiet(resp.header("Content-Length"));
        } catch (Throwable th) {
            return 0;
        } finally {
            if (resp != null) {
                try {
                    resp.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** Range 探测文件总长(HEAD 不可用时);失败/无长度返回 0 */
    private long probeRangeBytes(String url, Map<String, String> headers) {
        Response resp = null;
        try {
            Map<String, String> h = new HashMap<>(headers);
            h.put("Range", "bytes=0-0");
            Request.Builder builder = new Request.Builder().url(HttpClient.normalizeUrl(url));
            for (Map.Entry<String, String> e : h.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) builder.header(e.getKey(), e.getValue());
            }
            resp = sizeProbeClient().newCall(builder.build()).execute();
            if (!resp.isSuccessful())
                return 0;
            if (resp.code() == 206) {
                String cr = resp.header("Content-Range"); // 206: bytes 0-0/123456
                if (cr != null) {
                    Matcher m = CONTENT_RANGE_TOTAL.matcher(cr);
                    if (m.find()) {
                        long total = parseLongQuiet(m.group(1));
                        if (total > 0) return total;
                    }
                }
                // 206 却给不出总长(如 Content-Range: bytes 0-0/*):这一片按"长度未知"作废。
                // 注意**不能**退用 Content-Length —— 206 的 Content-Length 是"这段区间"的长度(这里是 1 字节),
                // 拿它当整片大小会把平均片大小算成 1 字节,推算结果彻底跑偏
                return 0;
            }
            // 200:服务器忽略了 Range,Content-Length 就是整个文件的长度,同样可用
            return parseLongQuiet(resp.header("Content-Length"));
        } catch (Throwable th) {
            return 0;
        } finally {
            if (resp != null) {
                try {
                    resp.close(); // 只取长度,不读体;close 即撤销剩余传输
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** {@code Content-Range: bytes 0-0/123456} 里的总长;静态复用 */
    private static final Pattern CONTENT_RANGE_TOTAL = Pattern.compile("/(\\d+)\\s*$");

    private static long parseLongQuiet(String s) {
        if (s == null) return 0;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 抽样探测专用客户端:在下载客户端基础上加 {@code callTimeout}(单请求含连接的硬上限)。
     * <p>不能直接用下载客户端:它的读超时是 60s,一个挂住的 CDN 能让 12 个样本串成十几分钟,
     * 把"任务启动"整个卡住。{@code newBuilder()} 共享连接池/线程池,不在热路径上重建连接栈。
     */
    private okhttp3.OkHttpClient sizeProbeClient() {
        okhttp3.OkHttpClient base = dm.downloadClient();
        okhttp3.OkHttpClient cached = sizeProbeClient;
        if (cached == null || sizeProbeBase != base) {
            cached = base.newBuilder()
                    .callTimeout(SIZE_SAMPLE_CALL_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS)
                    .build();
            sizeProbeClient = cached;
            sizeProbeBase = base;
        }
        return cached;
    }

    private volatile okhttp3.OkHttpClient sizeProbeClient;
    /** 缓存对应的下载客户端实例:DoH 变更会换掉它,此时探测客户端要跟着重建 */
    private volatile okhttp3.OkHttpClient sizeProbeBase;


    /** GET 完整文本响应(播放列表用);非 2xx/IO 失败返回 null */
    private String fetchText(String url, DownloadTask t) {
        try {
            Response resp = getDownloadResponse(url, baseHeaders(t));
            try {
                if (!resp.isSuccessful())
                    return null;
                return resp.body().string();
            } finally {
                resp.close();
            }
        } catch (Throwable th) {
            return null;
        }
    }

    /**
     * 判断响应是否为 m3u8 播放列表(按 Content-Type 或内容开头),不消费响应体;
     * 前 64 字节 trim 后匹配,防 BOM/空白/变体列表(EXT-X-)漏判导致 3KB 播放列表被当视频存盘
     */
    boolean isM3u8Response(Response resp) {
        try {
            String ct = resp.header("Content-Type");
            if (ct != null && (ct.contains("mpegurl") || ct.contains("mpeg-url") || ct.contains("apple"))) {
                return true;
            }
            okio.BufferedSource source = resp.body().source();
            source.request(64);
            okio.Buffer buf = source.getBuffer().clone();
            String head = buf.readUtf8(Math.min(64, buf.size())).trim();
            return head.startsWith("#EXTM3U") || head.contains("EXT-X-");
        } catch (Throwable th) {
            return false;
        }
    }

    /** 判断响应是否为 HTML/网页(防盗链页等),是则不应作为视频保存;不消费响应体 */
    boolean isHtmlResponse(Response resp) {
        try {
            String ct = resp.header("Content-Type");
            if (ct != null) {
                String lct = ct.toLowerCase(Locale.ROOT);
                if (lct.contains("text/html"))
                    return true;
            }
            okio.BufferedSource source = resp.body().source();
            source.request(32);
            okio.Buffer buf = source.getBuffer().clone();
            String head = buf.readUtf8(Math.min(32, buf.size())).toLowerCase(Locale.ROOT);
            return head.contains("<!doctype") || head.contains("<html") || head.contains("<script");
        } catch (Throwable th) {
            return false;
        }
    }

    /**
     * 响应头识别真实文件扩展名:Content-Disposition 的 filename 最可靠,其次按 Content-Type 映射。
     * 识别不出或为音频/未知类型返回 null(保持 URL 判定的扩展名)。
     */
    String detectExtensionFromResponse(Response resp) {
        try {
            String cd = resp.header("Content-Disposition");
            if (cd != null) {
                Matcher m = Pattern.compile("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?").matcher(cd);
                if (m.find()) {
                    String fn = m.group(1).trim();
                    int dot = fn.lastIndexOf('.');
                    if (dot >= 0 && dot < fn.length() - 1) {
                        String e = fn.substring(dot).toLowerCase(Locale.ROOT);
                        if (e.length() <= 5 && e.matches("\\.[a-z0-9]+"))
                            return e;
                    }
                }
            }
            String ct = resp.header("Content-Type");
            if (ct == null)
                return null;
            ct = ct.toLowerCase(Locale.ROOT);
            if (ct.contains("mpegurl") || ct.startsWith("audio/") || ct.contains("text/"))
                return null;
            if (ct.contains("matroska"))
                return ".mkv";
            if (ct.contains("webm"))
                return ".webm";
            if (ct.contains("quicktime"))
                return ".mov";
            if (ct.contains("mp2t") || ct.contains("mpegts") || ct.contains("mpeg-ts"))
                return ".ts";
            if (ct.contains("x-ms-wmv"))
                return ".wmv";
            if (ct.contains("3gpp"))
                return ".3gp";
            if (ct.contains("x-m4v"))
                return ".m4v";
            if (ct.contains("flv"))
                return ".flv";
            if (ct.contains("msvideo") || ct.contains("/avi"))
                return ".avi";
            if (ct.contains("mpeg"))
                return ".mpg";
            if (ct.contains("mp4") || ct.contains("mp4v"))
                return ".mp4";
        } catch (Throwable th) {
        }
        return null;
    }

    /**
     * 校验"磁盘上已有的成品文件"是否像视频(复用响应头的魔数规则,供入队自愈复用):
     * 文件不存在/读不出/长度过小 → false;扩展名非视频 → 按"不误伤"放行(与 {@link #isPlausibleVideo} 同口径)。
     */
    boolean looksLikeVideoFile(File f) {
        if (f == null || !f.exists() || !f.isFile() || f.length() < 16) return false;
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] head = new byte[16];
            int n = in.read(head);
            if (n <= 0) return false;
            byte[] use = n == head.length ? head : java.util.Arrays.copyOf(head, n);
            return isPlausibleVideo(use, f.getName());
        } catch (Throwable th) {
            return false;
        }
    }

    /**
     * 直链内容魔数校验:按扩展名检查响应体文件头, 防盗链占位页/错误响应(几KB文本或随机字节)
     * 与视频格式完全不符 → 返回 false 判失败; 非视频扩展名/无法判断一律放行(不误伤)。
     */
    boolean isPlausibleVideo(byte[] head, String fileName) {
        if (head == null || head.length < 4 || fileName == null)
            return true;
        String fn = fileName.toLowerCase(Locale.ROOT);
        if (fn.endsWith(".mp4") || fn.endsWith(".m4v") || fn.endsWith(".3gp") || fn.endsWith(".mov")) {
            return containsAscii(head, "ftyp") || containsAscii(head, "moov") || containsAscii(head, "mdat")
                    || containsAscii(head, "free");
        }
        if (fn.endsWith(".mkv") || fn.endsWith(".webm")) {
            return (head[0] & 0xFF) == 0x1A && (head[1] & 0xFF) == 0x45
                    && (head[2] & 0xFF) == 0xDF && (head[3] & 0xFF) == 0xA3;
        }
        if (fn.endsWith(".flv")) {
            return containsAscii(head, "FLV");
        }
        if (fn.endsWith(".ts") || fn.endsWith(".m2ts")) {
            // MPEG-TS 同步字节 0x47(可能在 0/188/376 偏移)
            return containsByte(head, (byte) 0x47);
        }
        return true;
    }

    private static boolean containsAscii(byte[] head, String s) {
        return indexOfAscii(head, s) >= 0;
    }

    /** 子串首次出现的字节下标(不存在为 -1);用于在头部窗口里按盒子名定位(moov/moof/mdat) */
    static int indexOfAscii(byte[] head, String s) {
        byte[] needle = s.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        outer: for (int i = 0; i + needle.length <= head.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (head[i + j] != needle[j])
                    continue outer;
            }
            return i;
        }
        return -1;
    }

    private static boolean containsByte(byte[] head, byte b) {
        for (byte v : head) {
            if (v == b)
                return true;
        }
        return false;
    }

    /**
     * 5.4 增强: 每任务限速（t.speedLimit 字节/秒, 0=不限速）。
     * <p>
     * 窗口状态存在任务上（{@code throttleWindowStart}/{@code throttleWindowBytes}）——
     * 原实现用的是方法内局部变量、每次调用都重置,elapsed 永远小于窗口 → 永远直接 return,限速从未生效。
     * 结算规则本身是纯计算,见 {@link com.github.tvbox.osc.util.ThrottlePolicy}（带单测）。
     */
    static void throttle(DownloadTask t, int written) {
        if (t.speedLimit <= 0)
            return;
        long now = System.currentTimeMillis();
        com.github.tvbox.osc.util.ThrottlePolicy.Decision d =
                com.github.tvbox.osc.util.ThrottlePolicy.decide(t.speedLimit, t.throttleWindowStart,
                        t.throttleWindowBytes + Math.max(0, written), now);
        t.throttleWindowStart = d.windowStart;
        t.throttleWindowBytes = d.windowBytes;
        if (d.sleepMs <= 0)
            return;
        try {
            Thread.sleep(d.sleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 保留中断标志,交给上层按暂停/取消处理
        }
    }

    /** 任务被暂停(用户/调度/网络)或已取消(删除记录)时,下载循环应中止 */
    static boolean isInterrupted(DownloadTask t) {
        return t.state != DownloadTask.STATE_DOWNLOADING || Thread.currentThread().isInterrupted();
    }
    /**
     * Bug3: 把 TS 拼接产物重封装为标准 MP4（MediaExtractor demux + MediaMuxer mux）。
     * Android MediaExtractor 支持 demux MPEG-TS，mux 出的 MP4 时长/缩略图正确（无需 ffmpeg）。
     *
     * <p>两处"以前只能回退 .ts"的坑在本方法里收口:
     * <ol>
     *   <li><b>空间</b>:重封装要写一份和源文件同样大的新 mp4(192/204 重打包时还要再多一份),
     *       而磁盘空间是真会不够的 —— 不够时 {@code MediaMuxer} 在 native 层写出错、{@code stop()} 报
     *       {@code -1007},用户拿到的还是 .ts。改为<b>动手前先算空间</b>:不够就先释放本任务的分片目录
     *       (合并产物已在,碎片已无用),仍不够则明确失败并说明原因(见 {@link #ensureRemuxSpace});</li>
     *   <li><b>{@code stop()} 报错</b>:实测 {@code MPEG4Writer} 已经把 moov 写进文件、每一帧也都编码完了,
     *       {@code stop()} 仍可能抛 "Error during stop(), muxer would have stopped already"(-1007)——
     *       此时产物其实是<b>可用</b>的,旧实现一抛异常就整份丢弃回退 .ts。改为<b>先校验产物</b>
     *       (见 {@link #mp4Usable}):可读、有时长、末尾样本还在 → 照旧采用;真的坏了才回退。</li>
     * </ol>
     *
     * @return true=重封装成功并已替换源文件;false=失败(调用方回退 .ts 后缀)
     */
    boolean remuxTsToMp4(DownloadTask t, File src) { return remuxer.remux(t, src); }

    static void ensureNoMedia(File dir) {
        try {
            File nm = new File(dir, ".nomedia");
            if (!nm.exists()) {
                // noinspection ResultOfMethodCallIgnored
                nm.createNewFile();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 格式化大小(供磁盘空间提示) */
    static String formatSize(long bytes) {
        if (bytes < 1024 * 1024)
            return String.format("%.0fKB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024)
            return String.format("%.1fMB", bytes / 1024.0 / 1024.0);
        return String.format("%.2fGB", bytes / 1024.0 / 1024.0 / 1024.0);
    }

    /** 缺失清单转可读串 "[1,3,7]"; 空清单返回 "[]" (日志全量落清单, 不截断) */
    static String missingList(List<Integer> missing) {
        if (missing == null || missing.isEmpty())
            return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < missing.size(); i++) {
            if (i > 0)
                sb.append(',');
            sb.append(missing.get(i));
        }
        return sb.append(']').toString();
    }
}
