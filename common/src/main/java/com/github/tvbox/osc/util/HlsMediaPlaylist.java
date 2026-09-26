package com.github.tvbox.osc.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HLS 媒体播放列表解析(纯逻辑,不依赖 Android,可 JVM 单测;放 :common 供下载侧复用)。
 *
 * <p>为什么需要它:下载侧原来只认"一行一个独立分片文件"的清单 —— 遇到 <b>fMP4({@code #EXT-X-MAP}
 * 的 init 段)</b> 与 <b>{@code #EXT-X-BYTERANGE}(分片其实是同一个文件里的一段字节)</b> 直接抛异常,
 * 整集下不了;而这两类正是当前大量 CDN 的实际形态(小文件被合并成一个大文件,清单只给范围)。
 * 解析必须落在纯逻辑层:隐式 offset、CRLF/BOM、异常清单的失败原因都能被 JVM 单测逐条钉住,
 * 下载执行器只负责"按解析结果去取字节",不再自己扫清单。
 *
 * <p>本类只解析<b>结构</b>(分片 URL + 可选字节范围 + 可选 init 段 + 加密属性原样透传),
 * 不做网络/文件操作,也不判"支不支持"(加密方式能否解、范围请求怎么发由下载侧决定)。
 * 解析不出来时<b>不抛异常</b>,在 {@link Result#error} 里给出明确原因(调用方据此报错/换线路)。
 */
public final class HlsMediaPlaylist {

    /** 换行:兼容 CRLF/LF(一份清单只解析一次,正则静态复用,不在循环里新建) */
    private static final Pattern LINE_BREAK = Pattern.compile("\r?\n");

    /** {@code #EXT-X-BYTERANGE} 的值:<len>[@<off>] */
    private static final Pattern BYTERANGE_VALUE = Pattern.compile("\\s*(\\d+)\\s*(?:@\\s*(\\d+)\\s*)?");

    /** 标签属性:与下载侧旧口径一致(带引号取引号内;不带引号取到逗号为止) */
    private static final Pattern ATTR_URI = Pattern.compile("URI=(\"[^\"]*\"|[^,]*)");
    private static final Pattern ATTR_METHOD = Pattern.compile("METHOD=(\"[^\"]*\"|[^,]*)");
    private static final Pattern ATTR_IV = Pattern.compile("IV=(\"[^\"]*\"|[^,]*)");
    private static final Pattern ATTR_BYTERANGE = Pattern.compile("BYTERANGE=(\"[^\"]*\"|[^,]*)");

    private static final String TAG_MAP = "#EXT-X-MAP:";
    private static final String TAG_BYTERANGE = "#EXT-X-BYTERANGE:";
    private static final String TAG_KEY = "#EXT-X-KEY:";
    private static final String TAG_MEDIA_SEQUENCE = "#EXT-X-MEDIA-SEQUENCE:";
    private static final String TAG_DISCONTINUITY = "#EXT-X-DISCONTINUITY";

    private HlsMediaPlaylist() {
    }

    // ------------------------------------------------------------------
    // 结构模型
    // ------------------------------------------------------------------

    /**
     * 字节范围 [offset, offset+length):{@code #EXT-X-BYTERANGE} 与 {@code #EXT-X-MAP} 的
     * {@code BYTERANGE} 属性共用。
     */
    public static final class ByteRange {
        public final long offset;
        public final long length;

        public ByteRange(long offset, long length) {
            this.offset = offset;
            this.length = length;
        }

        /** 半开区间末端(= 同一资源下一个省略 offset 的子范围的起点) */
        public long endExclusive() {
            return offset + length;
        }

        /** HTTP 请求头值:{@code bytes=<off>-<off+len-1>}(固定区间,与"从某字节到末尾"的续传 Range 不是一回事) */
        public String headerValue() {
            return "bytes=" + offset + "-" + (offset + length - 1);
        }

        /** 可读描述(日志用) */
        public String describe() {
            return length + "B@" + offset;
        }

        @Override
        public String toString() {
            return describe();
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof ByteRange)) return false;
            ByteRange r = (ByteRange) o;
            return r.offset == offset && r.length == length;
        }

        @Override
        public int hashCode() {
            return (int) (offset * 31 + length);
        }
    }

    /** 一个分片:URL + 可选字节范围 + 该片生效的加密信息(null=未加密) */
    public static final class Segment {
        public final String url;
        public final ByteRange range;
        public final Key key;

        Segment(String url, ByteRange range, Key key) {
            this.url = url;
            this.range = range;
            this.key = key;
        }

        @Override
        public String toString() {
            return url + (range == null ? "" : "[" + range.describe() + "]");
        }
    }

    /**
     * fMP4 的初始化段({@code #EXT-X-MAP}):整集一个,含 moov 等解码所需信息,不是分片 ——
     * 合并前必须先写它,否则产物只有 moof 片段、播放器解不出来。
     */
    public static final class InitSegment {
        public final String url;
        /** 一般来自独立的小文件(无范围);同一大文件内切片时才带范围 */
        public final ByteRange range;

        InitSegment(String url, ByteRange range) {
            this.url = url;
            this.range = range;
        }

        @Override
        public String toString() {
            return url + (range == null ? "" : "[" + range.describe() + "]");
        }
    }

    /**
     * 加密信息({@code #EXT-X-KEY}):本类只把属性原样带出来(URI 已补成绝对地址),
     * 是否支持该方法、IV 怎么用是下载侧的事(解不了就明确失败,而不是在这里假装能解)。
     */
    public static final class Key {
        public final String method;
        public final String uri;
        /** IV 原样十六进制串(可能为 null=按媒体序列号推导,见 HLS 规范) */
        public final String iv;

        Key(String method, String uri, String iv) {
            this.method = method;
            this.uri = uri;
            this.iv = iv;
        }

        @Override
        public String toString() {
            return method + "(" + uri + ")";
        }
    }

    /** 解析结果:{@link #error} 非空=清单不可用(原因明确);{@link #warnings} = 可继续但要留痕的异常 */
    public static final class Result {
        public final List<Segment> segments;
        /** null=不是 fMP4(清单里没有 {@code #EXT-X-MAP}) */
        public final InitSegment init;
        /** 首个分片的媒体序列号({@code #EXT-X-MEDIA-SEQUENCE};缺省 0) */
        public final long mediaSequence;
        public final List<String> warnings;
        public final String error;

        Result(List<Segment> segments, InitSegment init, long mediaSequence, List<String> warnings, String error) {
            this.segments = segments == null ? Collections.<Segment>emptyList()
                    : Collections.unmodifiableList(segments);
            this.init = init;
            this.mediaSequence = mediaSequence;
            this.warnings = warnings == null ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(warnings);
            this.error = error;
        }

        public boolean ok() {
            return error == null;
        }

        /** 是否 fMP4(有 init 段):产物本身就是 MP4,重封装失败也不能伪装成 .ts */
        public boolean isFmp4() {
            return init != null;
        }

        private static Result fail(String reason) {
            return new Result(null, null, 0, null, reason);
        }
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    /**
     * 解析媒体播放列表。
     *
     * @param playlistUrl 该清单自身的地址(相对地址按它补全;可为 null,此时相对地址原样返回)
     * @param text        清单文本(兼容 BOM/CRLF/空行/注释行)
     * @return 解析结果;失败时 {@link Result#error} 给出原因,不抛异常
     */
    public static Result parse(String playlistUrl, String text) {
        if (text == null || text.trim().isEmpty()) {
            return Result.fail("播放列表为空");
        }
        String content = M3u8Purifier.stripBom(text);
        List<Segment> segments = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        /** 每种资源的"下一个子范围起点":隐式 offset 就是上一条同资源分片的结束位置(按资源分别记) */
        Map<String, Long> nextOffsetOfResource = new HashMap<>();
        InitSegment init = null;
        Key curKey = null;
        LenOff pendingRange = null;
        long mediaSequence = 0;
        boolean warnedDiscontinuity = false;

        for (String raw : LINE_BREAK.split(content, -1)) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.charAt(0) == '#') {
                if (line.startsWith(TAG_MEDIA_SEQUENCE)) {
                    String v = line.substring(TAG_MEDIA_SEQUENCE.length()).trim();
                    try {
                        mediaSequence = Long.parseLong(v);
                    } catch (NumberFormatException e) {
                        warnings.add("EXT-X-MEDIA-SEQUENCE 非法(按 0 处理): " + v);
                        mediaSequence = 0;
                    }
                } else if (line.startsWith(TAG_KEY)) {
                    // METHOD 缺失按 NONE 处理(与下载侧旧行为一致:旧实现对 METHOD=NONE/缺失一律清空密钥)
                    String method = attr(line, ATTR_METHOD);
                    if (method == null || method.isEmpty() || "NONE".equalsIgnoreCase(method)) {
                        curKey = null;
                    } else {
                        String uri = attr(line, ATTR_URI);
                        if (uri == null || uri.isEmpty()) {
                            return Result.fail("EXT-X-KEY 缺少 URI(METHOD=" + method + ")");
                        }
                        curKey = new Key(method, resolveUrl(playlistUrl, uri), attr(line, ATTR_IV));
                    }
                } else if (line.startsWith(TAG_MAP)) {
                    String uri = attr(line, ATTR_URI);
                    if (uri == null || uri.isEmpty()) {
                        return Result.fail("EXT-X-MAP 缺少 URI(init 段地址未知,无法确定分片容器)");
                    }
                    String brText = attr(line, ATTR_BYTERANGE);
                    ByteRange range = null;
                    if (brText != null && !brText.trim().isEmpty()) {
                        LenOff p = parseByteRangeValue(brText);
                        if (p == null) {
                            return Result.fail("EXT-X-MAP 的 BYTERANGE 非法: " + brText);
                        }
                        // init 段没有"上一条同资源分片"可接(规范只对分片定义了隐式起点),省略 offset 一律按 0
                        range = new ByteRange(p.offset == null ? 0L : p.offset, p.length);
                    }
                    String mapUrl = resolveUrl(playlistUrl, uri);
                    if (range != null) {
                        // init 段与分片同处一个大文件时(init 也是该文件的一段),后续分片的隐式起点要接在它之后
                        nextOffsetOfResource.put(mapUrl, range.endExclusive());
                    }
                    if (init == null) {
                        init = new InitSegment(mapUrl, range);
                    } else if (!sameInit(init, mapUrl, range)) {
                        // 中途换 init 段意味着"整集一个 init"不成立,拼出来的产物必坏 —— 明确拒绝,不猜
                        return Result.fail("播放列表中途更换了 EXT-X-MAP(init 段),暂不支持");
                    }
                } else if (line.startsWith(TAG_BYTERANGE)) {
                    String v = line.substring(TAG_BYTERANGE.length()).trim();
                    LenOff p = parseByteRangeValue(v);
                    if (p == null) {
                        return Result.fail("EXT-X-BYTERANGE 非法(应为 <长度>[@<起点>]): " + v);
                    }
                    if (pendingRange != null) {
                        warnings.add("连续两个 EXT-X-BYTERANGE,前一个被覆盖: " + v);
                    }
                    pendingRange = p;
                } else if (line.startsWith(TAG_DISCONTINUITY) && !warnedDiscontinuity) {
                    warnedDiscontinuity = true;
                    warnings.add("清单含 EXT-X-DISCONTINUITY(分片时间戳可能重置,合并后由重封装按轨钳制)");
                }
                continue;
            }
            // 代理返回的 m3u8 可能被 HTML 包裹(如 <pre>...</pre>):含标签的行不是分片,跳过
            if (line.indexOf('<') >= 0 || line.indexOf('>') >= 0) continue;
            String url = resolveUrl(playlistUrl, line);
            ByteRange range = null;
            if (pendingRange != null) {
                range = applyOffset(pendingRange, url, nextOffsetOfResource, warnings);
                pendingRange = null;
            }
            segments.add(new Segment(url, range, curKey));
        }
        if (pendingRange != null) {
            warnings.add("清单末尾的 EXT-X-BYTERANGE 没有对应分片(已忽略)");
        }
        return new Result(segments, init, mediaSequence, warnings, null);
    }

    /** 同一次 {@code #EXT-X-MAP} 的重复声明(地址与范围都一样)可忽略;不同就要拒绝(见 parse) */
    private static boolean sameInit(InitSegment init, String url, ByteRange range) {
        if (!init.url.equals(url)) return false;
        if (init.range == null || range == null) return init.range == null && range == null;
        return init.range.equals(range);
    }

    /**
     * 给一个待应用的 {@code #EXT-X-BYTERANGE} 定出绝对起点,并记下该资源的下一个子范围起点。
     * <p>
     * 隐式 offset(清单只写长度)按 HLS 规范 = <b>上一条同资源分片的结束位置</b>,必须按资源分别记
     * (切片文件可能交替给出多份资源的范围)。规范要求此时"上一条同资源分片必须存在";
     * 真实现场偶有不守规矩的清单,此时按"资源起点"解释(唯一不破坏语义的读法),但留 warning 让日志可见。
     */
    private static ByteRange applyOffset(LenOff p, String url, Map<String, Long> nextOffsetOfResource,
            List<String> warnings) {
        long offset;
        if (p.offset != null) {
            offset = p.offset;
        } else {
            Long prevEnd = nextOffsetOfResource.get(url);
            if (prevEnd == null) {
                offset = 0L;
                warnings.add("EXT-X-BYTERANGE 省略 offset,但前面没有同资源分片(按起点 0 处理): " + url);
            } else {
                offset = prevEnd;
            }
        }
        nextOffsetOfResource.put(url, offset + p.length);
        return new ByteRange(offset, p.length);
    }

    /** {@code <len>[@<off>]}:长度必须为正整数;格式非法返回 null(调用方给明确原因) */
    private static LenOff parseByteRangeValue(String s) {
        if (s == null) return null;
        Matcher m = BYTERANGE_VALUE.matcher(s);
        if (!m.matches()) return null;
        try {
            long len = Long.parseLong(m.group(1));
            if (len <= 0) return null;
            Long off = m.group(2) == null ? null : Long.valueOf(Long.parseLong(m.group(2)));
            return new LenOff(len, off);
        } catch (NumberFormatException e) {
            return null; // 数值溢出(远超 long)同样按非法处理,不静默截断
        }
    }

    /** 取值:带引号取引号内,不带引号取到逗号为止(与下载侧原有口径一致) */
    private static String attr(String line, Pattern p) {
        Matcher m = p.matcher(line);
        if (!m.find()) return null;
        String v = m.group(1);
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        return v;
    }

    // ------------------------------------------------------------------
    // 地址补全(纯字符串,与下载侧 resolveUrl 同口径;下载侧那份依赖 android.net.Uri,这里不能引)
    // ------------------------------------------------------------------

    /**
     * 相对地址补全:绝对地址原样返回;协议相对({@code //host/x})补 scheme;
     * 站内绝对路径({@code /x})用清单地址的 authority(<b>含端口</b>)拼;其余按清单所在目录拼。
     */
    public static String resolveUrl(String playlistUrl, String ref) {
        if (ref == null || ref.isEmpty()) return ref;
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref;
        String scheme = schemeOf(playlistUrl);
        if (ref.startsWith("//")) return scheme + ":" + ref;
        if (ref.startsWith("/")) {
            String authority = authorityOf(playlistUrl);
            // 清单地址取不到 authority(异常输入)时原样返回,好过拼出 "http:///x" 这种必然 404 的地址
            if (authority == null || authority.isEmpty()) return ref;
            return scheme + "://" + authority + ref;
        }
        return dirOf(playlistUrl) + ref;
    }

    /** 清单所在目录(拼相对地址用;取不到返回空串) */
    private static String dirOf(String url) {
        if (url == null) return "";
        int i = url.lastIndexOf('/');
        return i > 0 ? url.substring(0, i + 1) : "";
    }

    private static String schemeOf(String url) {
        int i = url == null ? -1 : url.indexOf("://");
        return i > 0 ? url.substring(0, i) : "http";
    }

    private static String authorityOf(String url) {
        if (url == null) return "";
        int i = url.indexOf("://");
        if (i < 0) return "";
        int start = i + 3;
        int end = url.indexOf('/', start);
        return url.substring(start, end < 0 ? url.length() : end);
    }

    /** 待定范围的原始值(offset=null 表示清单省略了起点,要用上一条同资源分片的末尾) */
    private static final class LenOff {
        final long length;
        final Long offset;

        LenOff(long length, Long offset) {
            this.length = length;
            this.offset = offset;
        }
    }
}
