package com.github.tvbox.osc.download.internal;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.util.Log;

import com.github.tvbox.osc.bean.DownloadTask;
import com.github.tvbox.osc.download.DownloadSubType;
import com.github.tvbox.osc.util.HttpClient;
import com.github.tvbox.osc.util.SegmentUnwrapper;
import com.github.tvbox.osc.util.TsProbe;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Request;
import okhttp3.Response;

/**
 * 下载执行单元（BaseDownloadTask 的框架侧执行器）：直链下载与 HLS(m3u8) 分段下载/校验/合并。
 * 5.1 从 DownloadManager 按职责拆分，行为零变化；后续阶段将收敛为任务对象（4.6/4.7）。
 */
public class DownloadExecutor {

    /**
     * 连续多少片失败就判"系统性故障"直接失败(交给调度器重新解析地址/重试):
     * 单片抖动/个别 404 靠"跳过 + 补片 3 轮"吸收,但整条线路挂了时不能在几百片上白跑
     */
    private static final int MAX_CONSECUTIVE_SEGMENT_FAIL = 8;

    /** 补片轮次之间的等待(ms):分片 404/超时多是 CDN 抖动或地址过期,立刻重试通常还是同样结果 */
    private static final long REPAIR_ROUND_DELAY_MS = 1500L;

    /** 分片列表指纹文件名(与 segments.txt 同目录,用于识别"换线路后播放列表变了") */
    private static final String SEGMENTS_SIG = "segments.sig";

    /**
     * 分片"内容判定"需要攒够的头部字节数:够 TsProbe 按 188/192/204 对齐判真 TS,
     * 也够 SegmentUnwrapper 在容器头里(≤4KB)找到藏在后面的 TS 载荷
     */
    private static final int HEAD_PROBE_BYTES = 8192;

    private final DownloadManager dm;

    DownloadExecutor(DownloadManager dm) {
        this.dm = dm;
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
    public void downloadDirect(DownloadTask t) throws IOException {
        Map<String, String> headers = baseHeaders(t);
        // 续传前先按磁盘实况校正计数(与 HLS 侧 countExistingSegments 同一原则)
        reconcilePartWithDisk(t);
        if (t.downloadedBytes > 0) {
            headers.put("Range", "bytes=" + t.downloadedBytes + "-");
        }
        Response resp = getDownloadResponse(t.url, headers);
        dm.activeResponses.put(t.id, resp);
        try {
            // 内容级 HLS 识别:代理/伪装 URL 不含 .m3u8,但实际返回的是 m3u8 播放列表
            if (isM3u8Response(resp)) {
                Log.i("TVBox-Download", "内容识别为 m3u8,转 HLS 下载: " + t.fileName);
                resp.close();
                dm.activeResponses.remove(t.id);
                if (t.downloadedBytes > 0) {
                    t.downloadedBytes = 0;
                    FileCleaner.deleteQuietly(new File(t.partPath));
                }
                downloadHls(t);
                return;
            }
            int code = resp.code();
            if (code == 200 && t.downloadedBytes > 0) {
                // 服务器不支持断点,从头开始
                t.downloadedBytes = 0;
                FileCleaner.deleteQuietly(new File(t.partPath));
            } else if (code != 200 && code != 206) {
                throw new IOException("HTTP " + code);
            }
            // 内容校验:返回的是 HTML 网页/防盗链页而非视频,直接判失败,不保存垃圾文件
            if (isHtmlResponse(resp)) {
                throw new IOException("响应不是视频内容(可能为网页或防盗链页)");
            }
            if (t.totalBytes <= 0) {
                String cl = resp.header("Content-Length");
                if (cl != null) {
                    t.totalBytes = t.downloadedBytes + Long.parseLong(cl);
                }
            }
            // 响应头识别真实扩展名(代理/无后缀 URL 会隐藏格式):首次下载时在写 .part 前修正文件名
            if (t.downloadedBytes == 0) {
                String realExt = detectExtensionFromResponse(resp);
                if (realExt != null && !t.fileName.endsWith(realExt)) {
                    int dot = t.savePath.lastIndexOf('.');
                    String newPath = dot >= 0 ? t.savePath.substring(0, dot) + realExt : t.savePath + realExt;
                    // 改名查重:新路径若已有任务/文件则放弃改名,避免"同集两个任务"或覆盖已下载文件
                    boolean conflict = new File(newPath).exists();
                    if (!conflict) {
                        synchronized (dm.tasks) {
                            for (DownloadTask tt : dm.tasks) {
                                if (tt != t && tt.savePath != null && tt.savePath.equals(newPath)) {
                                    conflict = true;
                                    break;
                                }
                            }
                        }
                    }
                    if (!conflict) {
                        t.savePath = newPath;
                        t.partPath = t.savePath + ".part";
                        t.fileName = new File(t.savePath).getName();
                        Log.i("TVBox-Download", "响应头识别扩展名修正: " + t.fileName);
                        dm.persist();
                    }
                }
            }
            File part = new File(t.partPath);
            File parent = part.getParentFile();
            if (parent != null && !parent.exists())
                parent.mkdirs();
            // 内容魔数校验(防盗链占位/错误页拦截): peek 响应体前 16 字节不消费流,
            // 扩展名视频但文件头完全不符 → 判失败, 杜绝 3KB 之类的假"完成"文件
            if (t.downloadedBytes <= 0) {
                try {
                    okio.BufferedSource src = resp.body().source();
                    src.request(16);
                    okio.Buffer pb = src.getBuffer().clone();
                    int hn = (int) Math.min(16, pb.size());
                    byte[] head = new byte[hn];
                    if (hn > 0)
                        pb.readFully(head);
                    if (!isPlausibleVideo(head, t.fileName)) {
                        throw new IOException("响应内容与视频格式不符(可能为防盗链占位页或错误响应)");
                    }
                } catch (IOException e) {
                    throw e;
                } catch (Throwable ignored) {
                }
            }
            OutputStream os = new FileOutputStream(part, t.downloadedBytes > 0);
            try {
                InputStream is = resp.body().byteStream();
                byte[] buf = new byte[DownloadManager.BUFFER];
                int n;
                long lastPersist = 0;
                long lastSpeedTime = System.currentTimeMillis();
                long lastSpeedBytes = t.downloadedBytes;
                while ((n = is.read(buf)) > 0) {
                    if (isInterrupted(t)) {
                        os.flush();
                        t.speed = 0;
                        dm.persist();
                        dm.notifyChanged();
                        return;
                    }
                    os.write(buf, 0, n);
                    t.downloadedBytes += n;
                    throttle(t, n); // 5.4 增强: 每任务限速
                    long now = System.currentTimeMillis();
                    if (now - lastPersist > 500) {
                        // 实时网速:按时间窗口内的字节增量计算
                        long delta = now - lastSpeedTime;
                        if (delta > 0) {
                            t.speed = (long) ((t.downloadedBytes - lastSpeedBytes) * 1000.0 / delta);
                        }
                        lastSpeedTime = now;
                        lastSpeedBytes = t.downloadedBytes;
                        lastPersist = now;
                        // 进度:内存已实时更新,落盘/广播交由 flushProgress 节流合并(终态由外层强制落盘)
                        dm.flushProgress(t);
                    }
                }
                os.flush();
            } finally {
                os.close(); // 无论成功/异常/中断都关闭 FileOutputStream,避免 StrictMode "resource failed to call close"
            }
            t.speed = 0;
            if (isInterrupted(t)) {
                dm.persist();
                dm.notifyChanged();
                return;
            }
            // 完整性校验:服务器声明了 Content-Length 但实际字节不足 → 提前断开,
            // 判失败(保留 .part 可重试),绝不产出 3KB 之类的残缺"完成"文件
            if (t.totalBytes > 0 && t.downloadedBytes < t.totalBytes) {
                throw new IOException("下载不完整: 期望 " + t.totalBytes + " B,实际 " + t.downloadedBytes
                        + " B,服务器提前断开");
            }
            finishDirect(t);
        } finally {
            dm.activeResponses.remove(t.id);
            resp.close();
        }
    }

    /**
     * 续传前用 {@code .part} 的**实际长度**校正 {@code downloadedBytes}。
     * <p>
     * 原来直接信任计数器:计数器偏小 → 按错误偏移 append;偏大 → Range 起点错位,产物静默损坏。
     * 规则:磁盘优先;磁盘长度超过服务器声明总长说明上一次写入错位/文件被替换 → 丢弃重下。
     */
    private void reconcilePartWithDisk(DownloadTask t) {
        if (t.partPath == null) return;
        File partFile = new File(t.partPath);
        long onDisk = partFile.exists() ? partFile.length() : 0;
        if (t.totalBytes > 0 && onDisk > t.totalBytes) {
            Log.i("TVBox-Download", "续传校正: .part 长度 " + onDisk + " B 超过声明总长 "
                    + t.totalBytes + " B, 丢弃重下: " + t.fileName);
            onDisk = 0;
        }
        if (onDisk == 0 && partFile.exists()) {
            FileCleaner.deleteQuietly(partFile);
        }
        if (onDisk != t.downloadedBytes) {
            Log.i("TVBox-Download", "续传校正: 计数 " + t.downloadedBytes + " B → 磁盘 " + onDisk + " B: " + t.fileName);
            t.downloadedBytes = onDisk;
            dm.persist();
        }
    }

    private void finishDirect(DownloadTask t) throws IOException {
        File part = new File(t.partPath);
        File finalFile = new File(t.savePath);
        if (finalFile.getParentFile() != null && !finalFile.getParentFile().exists()) {
            finalFile.getParentFile().mkdirs();
        }
        if (finalFile.exists())
            finalFile.delete();
        if (!part.renameTo(finalFile)) {
            FileCleaner.copyFile(part, finalFile);
            FileCleaner.deleteQuietly(part);
        }
        t.state = DownloadTask.STATE_COMPLETED;
        t.downloadedBytes = t.totalBytes;
        DownloadLog.LOG.success(DownloadSubType.SAVE, "下载完成: " + t.fileName, DownloadLog.extras(t.episodeId));
        dm.archive.add(t); // 5.3: 完成写已下载档案(长期,先于清理)
        com.github.tvbox.osc.download.internal.DownloadNotifier.notifyCompleted(t); // 可选增强: 完成通知
        dm.persist();
        dm.notifyChanged();
    }

    // ------------------------------------------------------------------
    // HLS(m3u8)分段下载 + 合并
    // ------------------------------------------------------------------

    /** HLS 分段下载算法入口（4.6 任务对象化: 由 M3u8DownloadTask.doRun 委托） */
    public void downloadHls(DownloadTask t) throws IOException {
        String playlistUrl = t.url;
        String playlist = fetchPlaylist(playlistUrl, t);
        // 与播放链路用同一份净化规则(:common 的 M3u8Purifier,播放侧 PlayFragment 也是它):
        // ① 剔掉少数派分片(广告/占位)—— 这些片源站往往已经删了,播放器因为不看它们所以一路顺,
        //    下载器照单全下就会"播放不缺、下载缺"(实测 940 片里 8 片 404 全是少数派前缀);
        // ② 把清单内相对地址补成绝对地址(下载侧同样按播放列表目录解析,顺手统一)。
        // 返回 null = 无法判定(前缀分组过多/结构异常),与播放侧一样按"不净化"处理。
        String purified = com.github.tvbox.osc.util.M3u8Purifier.removeMinorityUrl(dirOfUrl(t.url), playlist);
        int filteredSegments = 0;
        if (purified != null && !purified.equals(playlist)) {
            filteredSegments = countSegmentLines(playlist) - countSegmentLines(purified);
            playlist = purified;
            if (filteredSegments > 0) {
                Log.i("TVBox-Download", "广告过滤: 剔除 " + filteredSegments + " 片少数派分片(与播放链路同一份清单): "
                        + t.fileName);
                DownloadLog.LOG.info(DownloadSubType.PLAYLIST, "广告过滤: 剔除 " + filteredSegments
                        + " 片少数派分片(与播放链路同一份清单,这些片不再请求)", DownloadLog.extras(t.episodeId));
            }
        }
        // 诊断: 完整播放列表内容(判断分片是否为加密HLS/占位/异常格式)
        Log.i("TVBox-Download", "播放列表内容(" + playlist.length() + "B): "
                + playlist.substring(0, Math.min(600, playlist.length())).replace("\n", "\\n"));
        // fetchPlaylist 遇到主播放列表时会切换到具体变体(t.url 已更新),分片需按实际播放列表解析
        List<HlsKey> segKeys = new ArrayList<>();
        List<String> segments = parseSegments(t.url, playlist, segKeys);
        if (segments.isEmpty()) {
            throw new IOException("m3u8 无有效分片");
        }
        boolean encrypted = false;
        for (HlsKey k : segKeys) {
            if (k != null) {
                encrypted = true;
                break;
            }
        }
        Log.i("TVBox-Download", "播放列表 " + segments.size() + " 片" + (encrypted ? "(AES-128 加密,分片解密后落盘)" : "")
                + ", 播放列表url=" + t.url + " 首片=" + segments.get(0));
        t.totalSegments = segments.size();
        // 续传起点以磁盘实况为准(不信任 TXT/内存计数):用户可能删过部分分片文件,
        // 若仍用 t.doneSegments 会跳过缺失分片直接合并导致失败。
        File tmpDir = segmentsDirOf(t);
        if (!tmpDir.exists())
            tmpDir.mkdirs();
        ensureNoMedia(tmpDir); // Bug5: 碎片目录放 .nomedia,防止 TS 碎片进系统相册
        int existing = countExistingSegments(tmpDir, segments.size());
        if (existing < t.doneSegments) {
            Log.i("TVBox-Download", "分片缺失(磁盘" + existing + "/" + segments.size() + ",记录" + t.doneSegments
                    + "),从缺失处续传: " + t.fileName);
        }
        t.doneSegments = existing;
        t.segmentBytes = 0;
        // 换线路保护:重新解析地址后如果分片列表变了,旧碎片属于另一份播放列表,必须丢弃重下
        // (否则两份视频的碎片会被合并成一个放不了的文件);列表一致则保留进度续传
        dropSegmentsIfPlaylistChanged(t, tmpDir, segments);
        // 下载前记录分段信息 TXT:来源/剧名/集数/碎片数/解析地址/分片列表/已完成(断点续传同步进度)
        writeSegmentsInfo(t, tmpDir, segments, t.doneSegments);

        long speedWindowStart = System.currentTimeMillis();
        long speedWindowBytes = 0;
        // 加密 HLS 的密钥缓存(按 keyUri 复用,整个任务只拉一次密钥)
        Map<String, byte[]> keyCache = new HashMap<>();
        // 分片级失败:单片失败不整体抛(记下来交给下面"校验+补片"重试,3 轮内仍缺才失败);
        // 但"连续多片都失败"说明是系统性故障(整条线路/播放列表失效),立刻失败交给调度器
        // "重新解析地址(换线路)"或重试,不必白跑完剩下的几百片
        IOException lastSegErr = null;
        int consecutiveFail = 0;
        /** 连续失败里"源侧永久失效"的片数(遇到一片非永久失败即清零):用来区分"线路挂了"与"这段分片源站就没有" */
        int consecutiveGone = 0;
        // "死片"记忆:已确认在源侧永久失效(HTTP 404/410)的分片序号。同一个地址再请求必然还是 404,
        // 记下来后补片轮次直接跳过,不再浪费请求(实测 8 片死片曾被反复请求 144 次,补片毫无进展)
        Set<Integer> goneSegments = new HashSet<>();
        // 只下载缺失的分片(跳过已存在且非空的分片),支持非连续缺失续传(如第3、7片被删)
        for (int i = 0; i < segments.size(); i++) {
            if (isInterrupted(t)) {
                t.speed = 0;
                dm.persist();
                dm.notifyChanged();
                return;
            }
            File segFile = new File(tmpDir, String.format("%05d.ts", i));
            if (segFile.exists() && segFile.length() > 0) {
                if (t.doneSegments <= i)
                    t.doneSegments = i + 1;
                consecutiveFail = 0;
                consecutiveGone = 0;
                continue; // 已存在,跳过
            }
            long segDone = 0; // 缺失分片从头下(无残留字节)
            try {
                downloadSegment(i, segments.get(i), segFile, segDone, t, segKeys.get(i), keyCache);
                consecutiveFail = 0;
                consecutiveGone = 0;
            } catch (IOException e) {
                if (isInterrupted(t)) {
                    // 本方暂停/取消导致的失败:与循环顶部的中断处理同语义,交给上层收尾
                    t.speed = 0;
                    dm.persist();
                    dm.notifyChanged();
                    return;
                }
                if (DownloadErrors.isNetworkError(e)) {
                    // 断网/超时:每片都会失败,整任务交给调度器按网络重试(带退避)处理
                    throw e;
                }
                if (DownloadErrors.isSegmentGone(e)) {
                    goneSegments.add(i); // 死片:后面补片轮次不再对它发请求
                    consecutiveGone++;
                } else {
                    consecutiveGone = 0;
                }
                lastSegErr = e;
                consecutiveFail++;
                Log.i("TVBox-Download", "分片失败(先跳过,交给补片重试): 片" + i + "/" + segments.size()
                        + " " + DownloadErrors.reasonOf(e));
                DownloadLog.LOG.warn(DownloadSubType.FAIL, "分片失败/片 " + i + ": " + DownloadErrors.reasonOf(e),
                        DownloadLog.extras(t.episodeId));
                if (consecutiveFail >= MAX_CONSECUTIVE_SEGMENT_FAIL) {
                    if (consecutiveGone >= MAX_CONSECUTIVE_SEGMENT_FAIL) {
                        // 连续失败全是"源侧永久失效"(例如播放列表尾部那段分片 CDN 上根本不存在):
                        // 后面每一片都会是同一个 404,不必把剩下的几百片白请求完 —— 记下死片就跳到校验/补片阶段,
                        // 由"缺片能否算完成"统一裁决(见 MissingSegmentPolicy 的放宽档与快速失败)
                        Log.i("TVBox-Download", "连续 " + consecutiveGone + " 片源侧永久失效,跳过剩余分片: " + t.fileName);
                        DownloadLog.LOG.warn(DownloadSubType.SEGMENT,
                                "连续 " + consecutiveGone + " 片源侧永久失效(HTTP 404/410),跳过剩余分片",
                                DownloadLog.extras(t.episodeId));
                        break;
                    }
                    throw new IOException("连续 " + consecutiveFail + " 片下载失败(" + DownloadErrors.ROUTE_SUSPECT_TEXT
                            + "): 最后错误 " + DownloadErrors.reasonOf(lastSegErr), lastSegErr);
                }
                continue; // doneSegments 不推进,交给后面"校验+补片"重试
            }
            if (t.doneSegments <= i)
                t.doneSegments = i + 1;
            t.segmentBytes = 0;
            // 注意: 不逐片写 segments.txt(每片全扫太浪费)——TXT 在校验/补片阶段统一写
            // 实时网速:按已完成分片的字节增量估算
            speedWindowBytes += segFile.length();
            long now = System.currentTimeMillis();
            if (now - speedWindowStart >= 500) {
                long delta = now - speedWindowStart;
                if (delta > 0) {
                    t.speed = (long) (speedWindowBytes * 1000.0 / delta);
                }
                speedWindowStart = now;
                speedWindowBytes = 0;
            }
            // 分片进度:doneSegments/segmentBytes 已实时写内存;落盘与广播节流到 450ms 窗口
            // (分片多时不再每片序列化写盘/刷屏),任务暂停/失败/完成由各终态点强制落盘,不丢状态
            dm.flushProgress(t);
        }
        t.speed = 0;

        // 碎片下载完,进入"文件校验中"。缺失清单驱动(4.7③): 首次全盘比对生成缺失清单(仅此一次全扫),
        // 每轮只补缺失清单项 + 只复检清单项(不反复全盘扫), 3 轮上限。
        t.message = DownloadManager.MSG_VERIFYING;
        dm.persist();
        dm.notifyChanged();
        // 首次全盘比对, 生成缺失清单(仅此一次 O(total))
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            File segFile = new File(tmpDir, String.format("%05d.ts", i));
            if (!segFile.exists() || segFile.length() <= 0)
                missing.add(i);
        }
        // 校验/开始 日志: 清单N片, 缺失M项:[序号](缺失清单全量落日志, 事后可核对)
        DownloadLog.LOG.info(DownloadSubType.VERIFY, "校验开始: 清单 " + segments.size() + " 片, 缺失 " + missing.size()
                + " 项" + missingList(missing), DownloadLog.extras(t.episodeId));
        int repair = 0;
        /** 缺片完成时的说明(空=没有任何缺片);完成态写进任务信息,让用户知道少了几秒 */
        String gapNote = "";
        /** 跨线路补片说明(4.8③,空=没补过);完成态写进任务信息,让用户知道有几片来自别的线路 */
        String patchNote = "";
        /** 跨线路补片只做一次(补不到就退回缺片完成/失败,不反复换线路拉列表) */
        boolean patchTried = false;
        /** 本线路播放列表的逐片时长:判定另一条线路"切分是否一致"的基准(见 HlsPlaylistLayout) */
        List<Double> primaryDurations = com.github.tvbox.osc.util.HlsPlaylistLayout.durations(playlist);
        /**
         * 缺片完成时"获准缺席"的分片序号。合并阶段必须跳过这些序号:
         * 磁盘上确实没有这个文件,而合并循环是逐序号拼接的,不跳过就会
         * {@code FileNotFoundException(ENOENT)} → 合并失败 → 整个任务重试(每次重跑下载)却永远出不来成品。
         */
        List<Integer> gapSegments = new ArrayList<>();
        while (!missing.isEmpty()) {
            // 剩余缺片是否全部已确认"源侧永久失效":是则补片/换线路都毫无意义,须走放宽档或立刻失败,
            // 不能一边明知拿不到、一边把 3 轮补片跑满(实测 8 片死片被反复请求 144 次、耗时几分钟却毫无进展)
            boolean allGone = goneSegments.containsAll(missing);
            if (repair >= DownloadManager.MAX_SEGMENT_REPAIR || allGone) {
                // 跨线路补片(4.8③):同线路补不齐(或全是死片)时,去这条集的另一条线路把"同一片"取回来。
                // 只取能证明是同一段的(切分一致 + 取回后 PTS 接缝校验),补到的片就是本集的分片,
                // 合并照旧 —— 不重下整集,也不放弃已下的其余全部内容。
                if (!patchTried && t.altRoutes != null && !t.altRoutes.isEmpty()) {
                    patchTried = true;
                    List<Integer> left = patchMissingFromAltRoutes(t, tmpDir, missing, primaryDurations);
                    int patched = missing.size() - left.size();
                    if (patched > 0) {
                        patchNote = "补 " + patched + " 片(来自其它线路)";
                        missing = left;
                        writeSegmentsInfo(t, tmpDir, segments, t.doneSegments);
                        if (missing.isEmpty()) break; // 补齐了:正常合并出完整文件
                        allGone = goneSegments.containsAll(missing);
                    }
                }
                // 补片 3 轮仍缺(或剩余缺片全是死片):先看是不是"极少数分片在源侧永久失效"(CDN 上就是没有这个文件,
                // 重试与换线路都拿不到)—— 为几秒钟画面把整集判死,对用户是净损失:
                // 按缺片完成,但必须在任务信息/日志里写清楚缺了几片,不允许静默;
                // 缺得多(超 MissingSegmentPolicy 阈值)才算失败。全部永久失效时用放宽档(见 MissingSegmentPolicy)。
                if (com.github.tvbox.osc.util.MissingSegmentPolicy.allowGapCompletion(
                        missing.size(), segments.size(), allGone)) {
                    gapNote = "缺 " + missing.size() + " 片";
                    gapSegments.addAll(missing); // 合并阶段按此清单跳过(见 gapSegments 注释)
                    Log.i("TVBox-Download", "补片" + (allGone ? "前已确认剩余缺片全是死片" : "3 轮仍缺") + " " + missing.size()
                            + " 片(源侧分片已失效),按缺片完成: " + t.fileName + " 缺失首片=" + missing.get(0));
                    DownloadLog.LOG.warn(DownloadSubType.REPAIR, "缺片完成: " + missing.size() + " 片在源侧已失效(共 "
                            + segments.size() + " 片)" + (allGone ? ",已确认补片/换线路均拿不到" : ",补片 "
                            + DownloadManager.MAX_SEGMENT_REPAIR + " 轮未补齐") + ":" + missingList(missing),
                            DownloadLog.extras(t.episodeId));
                    break;
                }
                // 补片 FAILED: 完整缺失清单落日志(不截断), 供事后核对; 保留碎片现场
                DownloadLog.LOG.fail(DownloadSubType.REPAIR, "补片 FAILED: " + (allGone ? "剩余缺片全是源侧永久失效" : "第 "
                        + DownloadManager.MAX_SEGMENT_REPAIR + " 轮仍缺失") + " " + missing.size() + " 片:"
                        + missingList(missing), DownloadLog.extras(t.episodeId));
                if (allGone) {
                    // 全是死片又超过放宽档:重试与重新解析地址都拿不到同一个 404,直接失败并说清原因,
                    // 让用户尽早看到"这集在源站已残缺"并换源,而不是干等重试
                    throw new IOException(
                            DownloadErrors.ALL_SEGMENTS_GONE + "(HTTP 404):共 " + missing.size()
                                    + " 片,超过可容忍范围(总计 " + segments.size()
                                    + " 片),重试与换线路均无法补齐,建议换源重下",
                            lastSegErr);
                }
                throw new IOException(
                        "碎片校验不一致,自动补下" + DownloadManager.MAX_SEGMENT_REPAIR + "轮后仍缺失(缺 " + missing.size() + " 片,如第"
                                + missing.get(0) + "片)"
                                + (lastSegErr == null ? "" : ",最后错误: " + DownloadErrors.reasonOf(lastSegErr)),
                        lastSegErr);
            }
            repair++;
            if (repair > 1) {
                // 轮间留点时间:分片 404/超时多是 CDN 抖动或地址过期,立刻重试通常是同样结果
                try {
                    Thread.sleep(REPAIR_ROUND_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            // 补片进度:每轮更新剩余片数,让"补片中(剩K片)"可见(而非一直卡在校验/合并入口)
            t.message = DownloadManager.MSG_REPAIRING + "(剩" + missing.size() + "片)";
            dm.persist();
            dm.notifyChanged();
            Log.i("TVBox-Download",
                    "碎片校验缺失 " + missing.size() + " 片,第" + repair + "/" + DownloadManager.MAX_SEGMENT_REPAIR
                            + "轮补下: " + t.fileName + " 缺失首片=" + missing.get(0));
            // 每轮补片开始: 目标[序号], 轮次 k/3
            DownloadLog.LOG.info(DownloadSubType.REPAIR, "补片第 " + repair + "/" + DownloadManager.MAX_SEGMENT_REPAIR
                    + " 轮开始: 目标 " + missing.size() + " 片" + missingList(missing), DownloadLog.extras(t.episodeId));
            // 只补缺失清单项; 单片失败不整体抛(留待下一轮, 3 轮内仍缺才失败)
            List<Integer> stillMissing = new ArrayList<>();
            int attempt = 0, okCount = 0, failCount = 0;
            for (int idx : missing) {
                File segFile = new File(tmpDir, String.format("%05d.ts", idx));
                if (!segFile.exists() || segFile.length() <= 0) {
                    if (goneSegments.contains(idx)) {
                        // 已确认在源侧永久失效:同一个 URL 再请求必然还是 404,不再浪费一次请求,留在缺失清单里
                        stillMissing.add(idx);
                        Log.i("TVBox-Download", "补片跳过死片(源侧已失效): 片" + idx);
                        continue;
                    }
                    attempt++;
                    try {
                        downloadSegment(idx, segments.get(idx), segFile, 0, t, segKeys.get(idx), keyCache);
                        // 单项补下成功: 片i 成功 bytes
                        DownloadLog.LOG.success(DownloadSubType.REPAIR, "补片/片 " + idx + " 成功 " + segFile.length() + "B",
                                DownloadLog.extras(t.episodeId));
                        okCount++;
                    } catch (IOException e) {
                        // 单项补下失败: 原因+HTTP码(留待下轮);同时记下最后原因,供最终失败提示带上
                        failCount++;
                        lastSegErr = e;
                        if (DownloadErrors.isSegmentGone(e)) {
                            // 死片:记下来,后面几轮不再对它发请求(否则 3 轮 × 每轮一次,同一片白请求多次)
                            goneSegments.add(idx);
                        }
                        Log.i("TVBox-Download", "补片失败(留待下轮): 片" + idx + " " + e.getMessage());
                        DownloadLog.LOG.fail(DownloadSubType.REPAIR, "补片/片 " + idx + " 失败 " + e.getMessage(),
                                DownloadLog.extras(t.episodeId));
                    }
                }
                // 只复检缺失清单项(不扫全目录)
                if (segFile.exists() && segFile.length() > 0) {
                    if (t.doneSegments <= idx)
                        t.doneSegments = idx + 1;
                } else {
                    stillMissing.add(idx);
                }
            }
            missing = stillMissing;
            writeSegmentsInfo(t, tmpDir, segments, t.doneSegments); // 每轮结束写一次 TXT(非每片)
            // 每轮补片结束: 补K, 成功K1, 失败K2, 剩余J:[序号]
            DownloadLog.LOG.info(DownloadSubType.REPAIR, "补片第 " + repair + "/" + DownloadManager.MAX_SEGMENT_REPAIR
                    + " 轮结束: 补" + attempt + " 成功" + okCount + " 失败" + failCount + " 剩余" + missing.size()
                    + " 片" + missingList(missing), DownloadLog.extras(t.episodeId));
        }
        t.doneSegments = segments.size(); // 全部就绪,进度=已下载分片数
        t.segmentBytes = 0;
        writeSegmentsInfo(t, tmpDir, segments, t.doneSegments);

        // 校验通过,进入"文件合并"
        t.message = DownloadManager.MSG_MERGING;
        dm.persist();
        dm.notifyChanged();
        // 合并尝试计数(第几次): >1 即重试,先记"合并/重试"(含上次失败原因还原上下文)
        t.mergeCount++;
        if (t.mergeCount > 1) {
            DownloadLog.LOG.warn(DownloadSubType.MERGE, "合并/重试 第 " + t.mergeCount + " 次开始, 上次失败原因: "
                    + (t.mergeFailReason == null || t.mergeFailReason.isEmpty() ? "未知" : t.mergeFailReason),
                    DownloadLog.extras(t.episodeId));
        }
        long mergeStart = System.currentTimeMillis();
        // 合并分片 -> mp4(双阶段原子合并):
        // 1) 先合并到分段目录内的 merged.tmp(过程文件,与成果隔离,崩溃最多损坏它)
        // 2) 完整后 rename 到最终文件(rename 为原子操作,要么成功要么未发生,杜绝半成品最终文件)
        File finalFile = new File(t.savePath);
        if (finalFile.getParentFile() != null && !finalFile.getParentFile().exists()) {
            finalFile.getParentFile().mkdirs();
        }
        File mergeTmp = new File(tmpDir, "merged.tmp");
        try {
            // 合并前空间检查:合并需额外写入约一个最终文件大小的 merged.tmp(分片已占空间),
            // 不足则失败并提示,避免合并中空间耗尽损坏
            long mergeSize = 0;
            for (int i = 0; i < segments.size(); i++) {
                mergeSize += new File(tmpDir, String.format("%05d.ts", i)).length();
            }
            if (mergeSize > 0) {
                File dir = new File(t.savePath).getParentFile();
                if (dir != null && dir.exists()) {
                    android.os.StatFs stat = new android.os.StatFs(dir.getAbsolutePath());
                    long free = stat.getAvailableBytes();
                    if (free - mergeSize < DownloadPolicy.MIN_FREE_SPACE) {
                        throw new IOException("磁盘空间不足,无法合并(完成后可用仅 "
                                + formatSize(Math.max(0, free - mergeSize)) + ",需清理约 "
                                + ((DownloadPolicy.MIN_FREE_SPACE - (free - mergeSize) + 1024 * 1024 - 1)
                                        / (1024 * 1024))
                                + "MB)");
                    }
                }
            }
            // 合并/开始 日志: 分片N, 缺失清单状态(已清空 / 缺片完成+片号), 分片总size, 目标路径
            DownloadLog.LOG.info(DownloadSubType.MERGE, "合并开始 第 " + t.mergeCount + " 次: 分片 " + segments.size()
                    + ", 缺失清单=" + (gapSegments.isEmpty() ? "已清空" : "缺片完成" + missingList(gapSegments))
                    + ", 分片总size=" + formatSize(mergeSize) + ", 目标 " + t.savePath,
                    DownloadLog.extras(t.episodeId));

            OutputStream out = new FileOutputStream(mergeTmp);
            try {
                long mergedBytes = 0;
                for (int i = 0; i < segments.size(); i++) {
                    if (t.state == DownloadTask.STATE_CANCELLED) {
                        throw new IOException("cancelled"); // Bug2: 删除记录后合并立即中止,不落最终文件
                    }
                    File segFile = new File(tmpDir, String.format("%05d.ts", i));
                    if (!segFile.exists() || segFile.length() <= 0) {
                        // 缺片完成:该片在源侧永久失效(补片 3 轮仍 404),磁盘上本就没有 —— 跳过拼接,
                        // 而不是抛 ENOENT 让整集合并失败。缺口处会少约几秒画面(已在完成信息里告知用户)。
                        if (gapSegments.contains(i)) {
                            Log.i("TVBox-Download", "合并跳过缺失分片(缺片完成): 片" + i + "/" + segments.size());
                            DownloadLog.LOG.warn(DownloadSubType.MERGE, "合并跳过缺失分片/片 " + i,
                                    DownloadLog.extras(t.episodeId));
                            continue;
                        }
                        // 未获准的缺口:磁盘碎片被外部删了/校验与合并之间的竞态,不能静默拼出残片
                        throw new IOException("合并时缺少分片 " + i + "/" + segments.size() + "(" + segFile.getName()
                                + " 不存在),碎片可能被清理,请重新下载");
                    }
                    FileCleaner.copyFile(segFile, out);
                    mergedBytes += segFile.length();
                    // 合并进度:每 20 片或最后一片更新一次(避免频繁写盘),"文件合并中(x%)"可见
                    // (否则几百MB拼接期间进度一直卡在 100% 不动,用户不知道进行到哪一步)
                    if (i % 20 == 0 || i == segments.size() - 1) {
                        int pct = (int) (mergedBytes * 100 / Math.max(1L, mergeSize));
                        t.message = DownloadManager.MSG_MERGING + "(" + pct + "%)";
                        // 合并进度同样节流:message 已实时更新,落盘/广播合并(终态完成会强制落盘)
                        dm.flushProgress(t);
                    }
                }
                out.flush();
            } finally {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
            // 原子替换:先删旧最终文件(若有),再 rename;rename 失败则复制兜底
            if (finalFile.exists())
                finalFile.delete();
            if (!mergeTmp.renameTo(finalFile)) {
                FileCleaner.copyFile(mergeTmp, finalFile);
                FileCleaner.deleteQuietly(mergeTmp);
            }
            // 合并/完成 日志: 最终size, 耗时ms
            DownloadLog.LOG.success(DownloadSubType.MERGE, "合并完成: 最终 " + formatSize(finalFile.length()) + ", 耗时 "
                    + (System.currentTimeMillis() - mergeStart) + "ms", DownloadLog.extras(t.episodeId));
            t.mergeFailReason = ""; // 合并成功,清空失败原因
        } catch (IOException e) {
            // 合并/失败 日志: 第k次, 原因(IO/缺片/校验不符), 碎片保留(不删 .ts, 供重试)
            t.mergeFailReason = e.getMessage() == null ? e.toString() : e.getMessage();
            DownloadLog.LOG.fail(DownloadSubType.MERGE, "合并失败 第 " + t.mergeCount + " 次, 原因: " + t.mergeFailReason
                    + ", 碎片保留", DownloadLog.extras(t.episodeId));
            throw e;
        }
        // 合并产物内容体检:既不是 TS 也不是 MP4 就不是音视频(常见:分片其实是图片/HTML 错误页,
        // 例如"整条线路返回 940 张 jpg")。这种绝不能算下载成功 —— 以前会"回退 .ts"照旧置 COMPLETED,
        // 用户拿到一个打不开的文件还不知道为什么。这里直接判失败,并把内容线索写进原因:
        // 调度器据此(见 shouldReResolve)自动重新解析地址换线路,提示里也能一眼看出是源的问题。
        TsProbe mergedProbe = probeHead(finalFile);
        boolean fragmentedOnly = mergedProbe.kind == TsProbe.Kind.MP4
                && ("moof".equals(mergedProbe.boxTag) || "styp".equals(mergedProbe.boxTag));
        if (!mergedProbe.isTs() && (mergedProbe.kind != TsProbe.Kind.MP4 || fragmentedOnly)) {
            String what = !mergedProbe.contentHint.isEmpty() ? mergedProbe.contentHint
                    : (fragmentedOnly ? "疑似 fMP4 片段缺 init 段" : "无法识别");
            String why = "下载内容不是完整视频(" + what + ",前 16 字节 " + mergedProbe.headHex + ")";
            Log.i("TVBox-Download", "合并产物体检不通过: " + t.fileName + " -> " + mergedProbe.describe());
            DownloadLog.LOG.fail(DownloadSubType.REMUX, "产物不是音视频,判失败: " + t.fileName + " | " + why
                    + "(该线路的分片地址可能已失效或被替换)", DownloadLog.extras(t.episodeId));
            // 删掉这个"假成品"(不能留在下载目录里当成果);碎片现场保留,换线路后按指纹决定是否复用
            FileCleaner.deleteQuietly(finalFile);
            throw new IOException(why + ":该线路的分片地址可能已失效或被替换,建议换线路或换源");
        }
        // Bug3: 合并产物是 TS 字节流(rename 成 .mp4 只是换后缀,时间戳不连续 -> 相册显示 1 秒)。
        // 重封装为标准 MP4(MediaExtractor demux + MediaMuxer mux,时长/缩略图正确);
        // 失败回退 .ts 后缀(不伪装 mp4),日志记录回退原因。
        t.message = DownloadManager.MSG_REMUX;
        dm.persist();
        dm.notifyChanged();
        if (remuxTsToMp4(finalFile)) {
            DownloadLog.LOG.success(DownloadSubType.REMUX, "重封装完成: " + t.fileName, DownloadLog.extras(t.episodeId));
        } else {
            if (t.savePath.toLowerCase(Locale.ROOT).endsWith(".mp4")) {
                String tsPath = t.savePath.substring(0, t.savePath.length() - 4) + ".ts";
                File tsFile = new File(tsPath);
                if (finalFile.renameTo(tsFile)) {
                    t.savePath = tsPath;
                    t.fileName = tsFile.getName();
                }
            }
            DownloadLog.LOG.warn(DownloadSubType.REMUX, "重封装失败,回退 .ts 后缀: " + t.fileName,
                    DownloadLog.extras(t.episodeId));
        }
        // 顺序铁律: 落盘 → 写档案 → 清理 → COMPLETED。
        // 清理(删碎片)是危险操作, 只有档案写成功后才允许; 档案写失败则保留碎片现场可重试。
        // 缺片完成/跨线路补片都要在任务信息里说清楚(档案/通知/日志都能看到),不允许静默完成
        String doneNote = "";
        if (!gapNote.isEmpty()) {
            doneNote = gapNote + (patchNote.isEmpty() ? "" : "," + patchNote)
                    + ",该分片在源侧已失效,可能少几秒画面";
        } else if (!patchNote.isEmpty()) {
            doneNote = patchNote + ",本线路缺的片已由其它线路补齐,合并为完整文件";
        }
        if (filteredSegments > 0) {
            // 与播放一致:清单里被判为广告/占位的少数派分片没有下(也不该出现在成品里),如实告诉用户
            String filterNote = "已过滤 " + filteredSegments + " 片广告/占位分片";
            doneNote = doneNote.isEmpty() ? filterNote : doneNote + "," + filterNote;
        }
        t.message = doneNote.isEmpty() ? "" : "已完成(" + doneNote + ")";
        t.state = DownloadTask.STATE_COMPLETED;
        DownloadLog.LOG.success(DownloadSubType.SAVE, "下载完成: " + t.fileName, DownloadLog.extras(t.episodeId));
        dm.archive.add(t); // 先写档案(长期)
        // 档案写成功后才清理分片目录(父级 tmp 保留)
        deleteSegmentsDir(t);
        t.tmpDir = null;
        com.github.tvbox.osc.download.internal.DownloadNotifier.notifyCompleted(t); // 可选增强: 完成通知
        dm.persist();
        dm.notifyChanged();
    }

    /**
     * 换线路保护:分片列表指纹与上次不一致 → 说明"重新解析地址"拿到的是<b>另一份播放列表</b>,
     * 磁盘上旧地址的碎片必须整目录丢弃重下(两份视频的碎片拼起来会得到放不了的文件);
     * 指纹一致(同一份列表,只是地址续期)则保留进度续传。<b>指纹缺失</b>(老任务/首次下载)按"无法判定"处理,
     * 保留现有碎片 —— 与改动前的行为一致,不会因为升级把在下的任务清空。
     */
    private void dropSegmentsIfPlaylistChanged(DownloadTask t, File tmpDir, List<String> segments) {
        String sig = com.github.tvbox.osc.util.SegmentListSignature.of(segments);
        if (sig.isEmpty()) return;
        File sigFile = new File(tmpDir, SEGMENTS_SIG);
        try {
            if (!sigFile.exists()) {
                writeText(sigFile, sig);
                return;
            }
            String old = readText(sigFile).trim();
            if (old.isEmpty() || sig.equals(old)) {
                writeText(sigFile, sig); // 缺失/一致:补写或原样保留
                return;
            }
            int dropped = countExistingSegments(tmpDir, segments.size());
            if (dropped > 0) {
                Log.i("TVBox-Download", "播放列表已变化(换线路/地址续期后分片不同),丢弃旧碎片 " + dropped
                        + " 片重下: " + t.fileName);
                DownloadLog.LOG.warn(DownloadSubType.REPAIR, "换线路: 播放列表变化,丢弃旧碎片 " + dropped + " 片重下: "
                        + t.fileName, DownloadLog.extras(t.episodeId));
            }
            // 整目录清碎片(含新旧数量不一致时多出来的尾巴);segments.txt/指纹/.nomedia 保留,随后会重写
            File[] leftovers = tmpDir.listFiles();
            if (leftovers != null) {
                for (File f : leftovers) {
                    String n = f.getName();
                    if (n.endsWith(".ts") || n.endsWith(".ts.part")) FileCleaner.deleteQuietly(f);
                }
            }
            t.doneSegments = 0;
            t.segmentBytes = 0;
            writeText(sigFile, sig);
        } catch (Throwable th) {
            // 指纹读写失败不影响下载(最坏情况与改动前一致:碎片混用)
            Log.i("TVBox-Download", "分片指纹处理异常(忽略): " + th);
        }
    }

    /**
     * 剥壳写盘:把 {@code [off, len)} 里的字节<b>按整 188 字节包</b>落盘(容器头已由调用方跳过),
     * 不足一包的尾巴存进 {@code carry} 留到下一块一起写 —— 流结束时剩下的那点尾巴直接丢弃
     * (实测包裹型分片尾部就是 20 字节的 PNG 收尾:CRC+IEND,混进 TS 会让后续包错位)。
     *
     * @return 新的 carry 长度(调用方据此算实际落盘字节数:{@code (before + len) - after})
     */
    private static int writeWholePackets(OutputStream os, byte[] carry, int carryLen,
            byte[] buf, int off, int len) throws IOException {
        if (carryLen > 0) {
            int need = SegmentUnwrapper.PACKET_SIZE - carryLen;
            int take = Math.min(need, len);
            System.arraycopy(buf, off, carry, carryLen, take);
            carryLen += take;
            off += take;
            len -= take;
            if (carryLen == SegmentUnwrapper.PACKET_SIZE) {
                os.write(carry, 0, SegmentUnwrapper.PACKET_SIZE);
                carryLen = 0;
            }
        }
        int whole = len / SegmentUnwrapper.PACKET_SIZE * SegmentUnwrapper.PACKET_SIZE;
        if (whole > 0) {
            os.write(buf, off, whole);
            off += whole;
            len -= whole;
        }
        if (len > 0) {
            System.arraycopy(buf, off, carry, 0, len);
            carryLen = len;
        }
        return carryLen;
    }

    private static void writeText(File f, String text) throws IOException {        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (OutputStream os = new FileOutputStream(f, false)) {
            os.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static String readText(File f) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                new java.io.FileInputStream(f), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /**
     * 在分段目录记录/更新分段信息 TXT:来源/剧名/集数/碎片数/解析地址/分片列表/已完成/分片状态。
     * 已完成 = 已下载完的连续分片数(断点续传起点);分片状态 = 逐片 1/0 标记(1=完成,0=缺失),
     * 支持非连续缺失(如用户删了第3、7片)时精确识别缺失分片。
     */
    private void writeSegmentsInfo(DownloadTask t, File tmpDir, List<String> segments, int doneCount) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("来源=").append(t.sourceName == null ? "" : t.sourceName).append('\n');
            sb.append("剧名=").append(t.vodName == null ? "" : t.vodName).append('\n');
            sb.append("集数=").append(t.episodeName == null ? "" : t.episodeName).append('\n');
            sb.append("碎片数=").append(segments.size()).append('\n');
            sb.append("解析地址=").append(t.url == null ? "" : t.url).append('\n');
            sb.append("分片列表=");
            for (int i = 0; i < segments.size(); i++) {
                if (i > 0)
                    sb.append(',');
                sb.append(String.format("%05d.ts", i));
            }
            sb.append('\n');
            sb.append("已完成=").append(Math.max(0, Math.min(doneCount, segments.size()))).append('\n');
            // 逐片状态:1=完成(存在且非空),0=缺失。全盘扫描磁盘实况,不依赖计数推断。
            sb.append("分片状态=");
            for (int i = 0; i < segments.size(); i++) {
                if (i > 0)
                    sb.append(',');
                File segFile = new File(tmpDir, String.format("%05d.ts", i));
                sb.append(segFile.exists() && segFile.length() > 0 ? '1' : '0');
            }
            sb.append('\n');
            File f = new File(tmpDir, DownloadManager.SEGMENTS_INFO);
            java.io.FileWriter fw = new java.io.FileWriter(f);
            try {
                fw.write(sb.toString());
            } finally {
                fw.close();
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    /** 读取分段信息 TXT 记录的"已完成"分片数;TXT 缺失/损坏返回 0 */
    int readSegmentsInfo(File tmpDir) {
        try {
            File info = new File(tmpDir, DownloadManager.SEGMENTS_INFO);
            if (!info.exists())
                return 0;
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(info));
            String line;
            int done = 0;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("已完成=")) {
                    try {
                        done = Integer.parseInt(line.substring(4).trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            br.close();
            return Math.max(0, done);
        } catch (Throwable th) {
            return 0;
        }
    }

    /**
     * 读取分段信息 TXT 的"分片状态"(逐片 1/0),返回缺失分片索引列表。
     * 支持非连续缺失(如第3、7片被删);TXT 缺失/无状态行时返回 null(调用方回退全盘扫描)。
     */
    private List<Integer> readMissingSegments(File tmpDir, int total) {
        try {
            File info = new File(tmpDir, DownloadManager.SEGMENTS_INFO);
            if (!info.exists())
                return null;
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(info));
            String line;
            String statusLine = null;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("分片状态=")) {
                    statusLine = line.substring("分片状态=".length());
                    break;
                }
            }
            br.close();
            if (statusLine == null || statusLine.isEmpty())
                return null;
            String[] parts = statusLine.split(",");
            List<Integer> missing = new ArrayList<>();
            for (int i = 0; i < parts.length && i < total; i++) {
                if (!"1".equals(parts[i].trim()))
                    missing.add(i);
            }
            return missing;
        } catch (Throwable th) {
            return null;
        }
    }

    /** 全盘扫描分段目录,统计"存在且非空"的分片数(续传/校验以磁盘实况为准,不信任TXT计数) */
    private int countExistingSegments(File tmpDir, int total) {
        int count = 0;
        for (int i = 0; i < total; i++) {
            File segFile = new File(tmpDir, String.format("%05d.ts", i));
            if (segFile.exists() && segFile.length() > 0)
                count++;
        }
        return count;
    }

    /**
     * 下载单个分片。
     *
     * @param segIndex 分片序号(仅用于失败分类:404/410 抛 {@link DownloadErrors.SegmentGoneException} 时带上序号,
     *                 让上层记住这片是"死片",不再重复请求)
     */
    private void downloadSegment(int segIndex, String segUrl, File segFile, long segDone, DownloadTask t,
            HlsKey key, Map<String, byte[]> keyCache) throws IOException {
        downloadSegment(segIndex, segUrl, segFile, segDone, t, key, keyCache, null);
    }

    /**
     * @param extraHeaders 额外请求头(覆盖任务自带的):跨线路补片时用备用线路解析出来的防盗链头
     *                     (每条线路可能各有各的 Referer),为 null 时只用任务自带的头
     */
    private void downloadSegment(int segIndex, String segUrl, File segFile, long segDone, DownloadTask t,
            HlsKey key, Map<String, byte[]> keyCache, Map<String, String> extraHeaders) throws IOException {
        // 加密分片无法断点续传(AES-CBC 需从头整段解密),一律整段下
        if (key != null)
            segDone = 0;
        // 上次这个分片是"包裹型"(已剥壳落盘):Range 偏移与原始字节对不上,必须整段重下
        File wrapMarker = new File(segFile.getAbsolutePath() + ".wrap");
        if (wrapMarker.exists()) {
            segDone = 0;
            FileCleaner.deleteQuietly(new File(segFile.getAbsolutePath() + ".part"));
        }
        Map<String, String> headers = baseHeaders(t);
        if (extraHeaders != null && !extraHeaders.isEmpty()) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) headers.put(e.getKey(), e.getValue());
            }
        }
        if (segDone > 0) {
            headers.put("Range", "bytes=" + segDone + "-");
        }
        Response resp = getDownloadResponse(segUrl, headers);
        dm.activeResponses.put(t.id, resp);
        try {
            int code = resp.code();
            if (code == 416) {
                // Range 超出文件末尾:该分段实际已完整(上次写入完成但进度未更新)。
                // 关闭本次响应,删除残片,不带 Range 从头整段重下,避免重试死循环
                Log.i("TVBox-Download", "分段416(Range超界),整段重下: " + segFile.getName());
                resp.close();
                dm.activeResponses.remove(t.id);
                FileCleaner.deleteQuietly(segFile);
                FileCleaner.deleteQuietly(new File(segFile.getAbsolutePath() + ".part"));
                segDone = 0;
                headers.remove("Range");
                resp = getDownloadResponse(segUrl, headers);
                dm.activeResponses.put(t.id, resp);
                code = resp.code();
            }
            if (code == 200 && segDone > 0) {
                segDone = 0;
                FileCleaner.deleteQuietly(segFile);
            } else if (code != 200 && code != 206) {
                Log.i("TVBox-Download", "分片 HTTP " + code + " url=" + segUrl);
                // 404/410 = 这个分片在源侧就是没有了(网盘/图床文件被删或从未上传):重新解析地址拿到的
                // 还是同一个 URL、还是 404,重试纯属浪费。单独抛"永久失效"让上层记住死片、跳过后续请求;
                // 其余状态码(超时/5xx/403/451/断网)仍按"可补救"处理,该换线路换线路、该重试重试。
                if (code == 404 || code == 410) {
                    throw new DownloadErrors.SegmentGoneException(segIndex, "分片下载失败(HTTP " + code
                            + ",该分片在" + DownloadErrors.SEGMENT_GONE_TEXT + ",重试与换线路均无法补齐)");
                }
                // 文案要求:可读 + 保留 "HTTP 403" 这种状态码 —— 调度器据此判定"地址可能过期"并自动重新解析地址(换线路),
                // 提示里也要能看出是源的分片失效而不是本机问题
                throw new IOException("分片下载失败(HTTP " + code + ")");
            }
            File parent = segFile.getParentFile();
            if (parent != null && !parent.exists())
                parent.mkdirs();

            // 构建输入流:加密分片经 AES-128-CBC 边下边解密(明文落盘,续传/校验/合并/封装流程不变),
            // 非加密分片即原始字节流。密钥按 keyUri 缓存,整任务只拉一次。
            InputStream is = resp.body().byteStream();
            if (key != null) {
                byte[] keyBytes = loadKeyBytes(key, t, keyCache);
                try {
                    javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
                    cipher.init(javax.crypto.Cipher.DECRYPT_MODE,
                            new javax.crypto.spec.SecretKeySpec(keyBytes, "AES"),
                            new javax.crypto.spec.IvParameterSpec(key.iv));
                    is = new javax.crypto.CipherInputStream(is, cipher);
                } catch (java.security.GeneralSecurityException e) {
                    throw new IOException("HLS 解密初始化失败: " + e.getMessage(), e);
                }
            }

            // Bug2: 分片先写 .part 再 rename 原子落盘——进程被杀不产生"残缺但非空"的 .ts,
            // 续传/校验只信任 rename 后的完整分片
            File partFile = new File(segFile.getAbsolutePath() + ".part");
            OutputStream os = new FileOutputStream(partFile, segDone > 0);
            try {
                byte[] buf = new byte[DownloadManager.BUFFER];
                int n;
                long written = segDone;
                // 内容判定(只在整段重下做):先攒够头部再判断"是不是音视频/是不是包裹型分片" ——
                // 旧判据"首 8 字节里有 0x47"会被 PNG 签名(89 50 4E 47 里正好有个 'G')骗过,
                // 现在用 TsProbe 按 188/192/204 对齐判真 TS,并识别 PNG/JPEG 壳里的 TS 载荷(剥壳)
                int payloadOffset = 0;      // >0 = 剥壳:从该偏移起才是 TS
                int carryLen = 0;           // 剥壳时不足 188 包、留到下一块一起写
                byte[] carry = new byte[SegmentUnwrapper.PACKET_SIZE];
                byte[] headBuf = new byte[HEAD_PROBE_BYTES];
                int headLen = 0;
                boolean decided = segDone > 0;  // 续传已有内容:维持原形态,不再判定
                while ((n = is.read(buf)) > 0) {
                    if (!decided) {
                        int copy = Math.min(n, headBuf.length - headLen);
                        System.arraycopy(buf, 0, headBuf, headLen, copy);
                        headLen += copy;
                        if (headLen < headBuf.length) {
                            continue; // 头没攒够:先不落盘,继续读
                        }
                        decided = true;
                        com.github.tvbox.osc.util.TsProbe probe = com.github.tvbox.osc.util.TsProbe.of(headBuf);
                        if (!probe.isTs() && probe.kind != com.github.tvbox.osc.util.TsProbe.Kind.MP4) {
                            com.github.tvbox.osc.util.SegmentUnwrapper.Plan plan =
                                    com.github.tvbox.osc.util.SegmentUnwrapper.plan(headBuf, resp.body().contentLength());
                            if (plan != null) {
                                payloadOffset = plan.offset;
                                Log.i("TVBox-Download", "分片是包裹型(" + probe.contentHint + " 壳里藏 TS),剥壳下载: "
                                        + segFile.getName() + " 头 " + plan.offset + " 字节,载荷 "
                                        + (plan.length < 0 ? "至流末尾" : plan.length + "B"));
                                DownloadLog.LOG.info(DownloadSubType.SEGMENT, "包裹型分片剥壳: " + segFile.getName()
                                        + " 丢弃容器头 " + plan.offset + "B", DownloadLog.extras(t.episodeId));
                                try {
                                    writeText(wrapMarker, "1");
                                } catch (Throwable ignored) {
                                }
                            } else {
                                FileCleaner.deleteQuietly(partFile);
                                throw new IOException("分片内容不是视频("
                                        + (probe.contentHint.isEmpty() ? "无法识别" : probe.contentHint)
                                        + ",可能防盗链错误响应)");
                            }
                        }
                        // 头部按最终形态落盘(剥壳:跳过容器头;整包截断交给对齐写入)
                        int headSrcLen = headLen - payloadOffset;
                        if (payloadOffset > 0) {
                            int before = carryLen;
                            carryLen = writeWholePackets(os, carry, carryLen, headBuf, payloadOffset, headSrcLen);
                            written += (before + headSrcLen) - carryLen;
                        } else {
                            os.write(headBuf, 0, headSrcLen);
                            written += headSrcLen;
                        }
                        // 本次 read 里超出头部缓冲的那一段(还没落盘)按同样规则接着写
                        int excess = n - copy;
                        if (excess > 0) {
                            if (payloadOffset > 0) {
                                int before = carryLen;
                                carryLen = writeWholePackets(os, carry, carryLen, buf, copy, excess);
                                written += (before + excess) - carryLen;
                            } else {
                                os.write(buf, copy, excess);
                                written += excess;
                            }
                        }
                        t.segmentBytes = written;
                        throttle(t, n);
                        continue;
                    }
                    if (isInterrupted(t)) {
                        os.flush();
                        t.segmentBytes = written;
                        dm.persist();
                        return;
                    }
                    if (payloadOffset > 0) {
                        int before = carryLen;
                        carryLen = writeWholePackets(os, carry, carryLen, buf, 0, n);
                        written += (before + n) - carryLen;
                    } else {
                        os.write(buf, 0, n);
                        written += n;
                    }
                    t.segmentBytes = written;
                    throttle(t, n); // 5.4 增强: 每任务限速
                }
                // 流结束:头都没攒够(极短响应)时也要判一次,避免把错误页当分片收下
                if (!decided) {
                    byte[] head = java.util.Arrays.copyOf(headBuf, headLen);
                    com.github.tvbox.osc.util.TsProbe probe = com.github.tvbox.osc.util.TsProbe.of(head);
                    if (probe.isTs() || probe.kind == com.github.tvbox.osc.util.TsProbe.Kind.MP4) {
                        os.write(head, 0, headLen);
                        written += headLen;
                    } else {
                        com.github.tvbox.osc.util.SegmentUnwrapper.Plan plan =
                                com.github.tvbox.osc.util.SegmentUnwrapper.plan(head, headLen);
                        if (plan == null) {
                            FileCleaner.deleteQuietly(partFile);
                            throw new IOException("分片内容不是视频("
                                    + (probe.contentHint.isEmpty() ? "无法识别" : probe.contentHint) + ")");
                        }
                        // 极短的包裹型分片:同样跳过容器头、只落整包
                        try {
                            writeText(wrapMarker, "1");
                        } catch (Throwable ignored) {
                        }
                        carryLen = writeWholePackets(os, carry, carryLen, head, plan.offset, headLen - plan.offset);
                        written += (headLen - plan.offset) - carryLen;
                    }
                    t.segmentBytes = written;
                }
                os.flush();
            } finally {
                os.close(); // 无论成功/异常/中断都关闭 FileOutputStream,避免 StrictMode "resource failed to call close"
            }
            if (!partFile.renameTo(segFile)) {
                FileCleaner.copyFile(partFile, segFile);
                FileCleaner.deleteQuietly(partFile);
            }
        } finally {
            dm.activeResponses.remove(t.id);
            resp.close();
        }
    }

    // ------------------------------------------------------------------
    // 跨线路补片(4.8③):本线路缺的片,去这条集的另一条线路把"同一片"取回来
    // ------------------------------------------------------------------

    /** 一次跨线路补片最多补几片:再多就接近"整集重下",不如走换线路/缺片完成那两条路 */
    private static final int MAX_ALT_PATCH_SEGMENTS = 30;
    /** PTS 探测窗口(字节):够覆盖几十个 PES 头 */
    private static final int PTS_WINDOW_BYTES = 64 * 1024;

    /** 一条可用备用线路的媒体播放列表(切分已证明与本线路一致,才允许取片) */
    private static final class AltPlaylist {
        final com.github.tvbox.osc.bean.DownloadRoute route;
        final List<String> segments;
        final List<HlsKey> keys;
        /** 该线路解析出来的防盗链请求头(每条线路可能各有各的 Referer) */
        final Map<String, String> headers;

        AltPlaylist(com.github.tvbox.osc.bean.DownloadRoute route, List<String> segments, List<HlsKey> keys,
                Map<String, String> headers) {
            this.route = route;
            this.segments = segments;
            this.keys = keys;
            this.headers = headers;
        }
    }

    /**
     * 跨线路补片:把仍然缺失的分片,从这条集的其它线路取"同一片"补上。
     *
     * <p>为什么不是"直接换线路整集重下":该下的大部分已经下完了,缺的往往只有几片 ——
     * 只补这几片,流量与时间都是一个零头,而且不用把已下的全部作废。
     *
     * <p>安全前提(必须可证,不猜):
     * <ol>
     *   <li><b>切分一致</b>:候选线路的播放列表片数相同、每片 {@code #EXTINF} 时长逐个在容差内
     *       ({@link com.github.tvbox.osc.util.HlsPlaylistLayout})—— 只有这样"第 i 片"才是同一段时间。
     *       两条线路是不同转码/不同切分时(如 A 940 片×6s、B 470 片×12s),拿来的片拼进去文件不报错,
     *       但时间轴错乱(花屏/音画不同步),所以宁可不补;</li>
     *   <li><b>取回后接缝校验</b>:替补片必须是标准 188 包 TS,且首/末 PTS 与本线路相邻已下分片接得上
     *       ({@link com.github.tvbox.osc.util.TsPtsProbe#continuityProblem})。</li>
     * </ol>
     * 任一校验不过 → 删掉替补片、继续试下一条线路;全部不行就退回"缺片完成/失败",绝不拼出坏文件。
     *
     * @return 仍未补齐的分片序号(补上的已不在其中)
     */
    private List<Integer> patchMissingFromAltRoutes(DownloadTask t, File tmpDir, List<Integer> missing,
            List<Double> primaryDurations) {
        List<Integer> remain = new ArrayList<>(missing);
        if (missing.isEmpty() || t.altRoutes == null || t.altRoutes.isEmpty()) return remain;
        if (missing.size() > MAX_ALT_PATCH_SEGMENTS) {
            Log.i("TVBox-Download", "跨线路补片跳过:缺 " + missing.size() + " 片超过上限 "
                    + MAX_ALT_PATCH_SEGMENTS + "(接近整集重下): " + t.fileName);
            return remain;
        }
        if (primaryDurations.isEmpty()) {
            Log.i("TVBox-Download", "跨线路补片跳过:本线路播放列表没有 #EXTINF 时长,无法判定切分一致: " + t.fileName);
            return remain;
        }
        // 候选线路先各自探测一次(重解析地址 + 拉列表 + 比切分),能用的留着后面逐片取
        List<AltPlaylist> alts = new ArrayList<>();
        for (com.github.tvbox.osc.bean.DownloadRoute r : t.altRoutes) {
            AltPlaylist a = loadAltPlaylist(t, r, primaryDurations);
            if (a != null) alts.add(a);
        }
        if (alts.isEmpty()) {
            Log.i("TVBox-Download", "跨线路补片:没有切分一致的备用线路可用: " + t.fileName);
            DownloadLog.LOG.warn(DownloadSubType.REPAIR, "跨线路补片: 备用线路均不可用(切分不一致/解析失败/非 m3u8)",
                    DownloadLog.extras(t.episodeId));
            return remain;
        }
        List<Integer> left = new ArrayList<>();
        int patched = 0;
        Map<String, byte[]> keyCache = new HashMap<>();
        for (int idx : missing) {
            boolean ok = false;
            for (AltPlaylist a : alts) {
                if (idx >= a.segments.size()) continue;
                try {
                    if (patchOneSegment(t, tmpDir, idx, a, keyCache)) {
                        ok = true;
                        patched++;
                        break;
                    }
                } catch (Throwable th) {
                    Log.i("TVBox-Download", "跨线路补片失败(片" + idx + " 线路 " + a.route.describe() + "): "
                            + DownloadErrors.reasonOf(th));
                }
            }
            if (!ok) left.add(idx);
        }
        Log.i("TVBox-Download", "跨线路补片完成: 补上 " + patched + " 片,仍缺 " + left.size() + " 片: " + t.fileName);
        DownloadLog.LOG.warn(DownloadSubType.REPAIR, "跨线路补片: 补上 " + patched + " 片(来自其它线路),仍缺 "
                + left.size() + " 片" + missingList(left), DownloadLog.extras(t.episodeId));
        return left;
    }

    /**
     * 探测一条备用线路:重解析地址 → 拉它的播放列表(只读,不改任务字段)→ 逐片时长与本线路比对。
     * 任一步失败/切分不一致都返回 null(这条线路的片不能用来补)。
     */
    private AltPlaylist loadAltPlaylist(DownloadTask t, com.github.tvbox.osc.bean.DownloadRoute route,
            List<Double> primaryDurations) {
        try {
            com.github.tvbox.osc.spiderapi.ResolveResult rr =
                    dm.urlResolverApi.resolvePlayUrl(t.sourceKey, route.playFlag, route.episodeRawUrl);
            if (rr == null || rr.url == null || rr.url.isEmpty()) {
                Log.i("TVBox-Download", "跨线路补片:线路 " + route.describe() + " 解析不出地址");
                return null;
            }
            if (!rr.url.toLowerCase().contains(".m3u8")) {
                // 直链线路:是另一个文件,切分无从比对(整段字节级对齐无从校验),不拿来补
                Log.i("TVBox-Download", "跨线路补片:线路 " + route.describe() + " 不是 m3u8(直链不参与补片)");
                return null;
            }
            String[] pl = fetchPlaylistReadOnly(rr.url, t, rr.headers);
            List<HlsKey> keys = new ArrayList<>();
            List<String> segs = parseSegments(pl[1], pl[0], keys);
            if (segs.isEmpty()) {
                Log.i("TVBox-Download", "跨线路补片:线路 " + route.describe() + " 播放列表无有效分片");
                return null;
            }
            com.github.tvbox.osc.util.HlsPlaylistLayout.Comparison cmp =
                    com.github.tvbox.osc.util.HlsPlaylistLayout.compare(primaryDurations,
                            com.github.tvbox.osc.util.HlsPlaylistLayout.durations(pl[0]));
            if (!cmp.same) {
                // 关键拒绝:不同切分的线路拿来的"第 i 片"不是同一段,拼进去就是时间轴错乱
                Log.i("TVBox-Download", "跨线路补片:线路 " + route.describe() + " 切分不一致(" + cmp.reason + "),不补");
                DownloadLog.LOG.warn(DownloadSubType.REPAIR, "跨线路补片: 线路 " + route.describe()
                        + " 切分不一致,不参与补片(" + cmp.reason + ")", DownloadLog.extras(t.episodeId));
                return null;
            }
            Log.i("TVBox-Download", "跨线路补片:线路 " + route.describe() + " 切分一致(" + segs.size() + " 片),可用于补片");
            return new AltPlaylist(route, segs, keys, rr.headers);
        } catch (Throwable th) {
            Log.i("TVBox-Download", "跨线路补片:线路 " + route.describe() + " 探测异常: " + DownloadErrors.reasonOf(th));
            return null;
        }
    }

    /** 取一片:下载 → 校验(内容 + PTS 接缝)→ 不合格就删掉;返回是否补成 */
    private boolean patchOneSegment(DownloadTask t, File tmpDir, int idx, AltPlaylist alt,
            Map<String, byte[]> keyCache) throws IOException {
        File segFile = new File(tmpDir, String.format("%05d.ts", idx));
        if (segFile.exists() && segFile.length() > 0) return true; // 已存在:不用补
        HlsKey key = idx < alt.keys.size() ? alt.keys.get(idx) : null;
        // 走本任务的分片下载通道:剥壳/188 包对齐/AES 解密口径与主流程完全一致;
        // 请求头用该线路自己解析出来的(每条线路可能各有各的 Referer)
        downloadSegment(idx, alt.segments.get(idx), segFile, 0, t, key, keyCache, alt.headers);
        String problem = verifyPatchedSegment(tmpDir, idx, segFile);
        if (problem != null) {
            FileCleaner.deleteQuietly(segFile);
            FileCleaner.deleteQuietly(new File(segFile.getAbsolutePath() + ".part"));
            Log.i("TVBox-Download", "跨线路补片校验不过(片" + idx + " 线路 " + alt.route.describe() + "): " + problem);
            DownloadLog.LOG.warn(DownloadSubType.REPAIR, "跨线路补片校验不过/片 " + idx + "("
                    + alt.route.describe() + "): " + problem, DownloadLog.extras(t.episodeId));
            return false;
        }
        if (t.doneSegments <= idx) t.doneSegments = idx + 1;
        Log.i("TVBox-Download", "跨线路补片成功: 片" + idx + " 来自线路 " + alt.route.describe()
                + " " + segFile.length() + "B");
        DownloadLog.LOG.success(DownloadSubType.REPAIR, "跨线路补片/片 " + idx + " 来自 " + alt.route.describe()
                + " " + segFile.length() + "B", DownloadLog.extras(t.episodeId));
        return true;
    }

    /**
     * 替补分片校验:内容是可解析的标准 188 包 TS,且首/末 PTS 与本线路相邻已下分片的时间轴接得上。
     *
     * @return null=通过;非 null=拒绝原因
     */
    private String verifyPatchedSegment(File tmpDir, int idx, File segFile) {
        byte[] head = readWindow(segFile, PTS_WINDOW_BYTES, false);
        if (head == null || head.length == 0) return "分片为空";
        com.github.tvbox.osc.util.TsProbe probe = com.github.tvbox.osc.util.TsProbe.of(head);
        if (!probe.isTs()) {
            return "内容不是 TS(" + (probe.contentHint.isEmpty() ? probe.kind.name() : probe.contentHint) + ")";
        }
        if (probe.kind != com.github.tvbox.osc.util.TsProbe.Kind.TS_188) {
            // 192/204 包与已下分片形态不同,MediaExtractor 侧口径也不一样,不掺进来
            return "不是标准 188 包 TS(" + probe.kind.name() + ")";
        }
        double subFirst = com.github.tvbox.osc.util.TsPtsProbe.firstPtsSeconds(head, head.length);
        byte[] tail = readWindow(segFile, PTS_WINDOW_BYTES, true);
        double subLast = com.github.tvbox.osc.util.TsPtsProbe.lastPtsSeconds(tail, tail.length);
        double prevLast = -1, nextFirst = -1;
        if (idx > 0) {
            File prev = new File(tmpDir, String.format("%05d.ts", idx - 1));
            if (prev.exists() && prev.length() > 0) {
                byte[] t1 = readWindow(prev, PTS_WINDOW_BYTES, true);
                prevLast = com.github.tvbox.osc.util.TsPtsProbe.lastPtsSeconds(t1, t1.length);
            }
        }
        File next = new File(tmpDir, String.format("%05d.ts", idx + 1));
        if (next.exists() && next.length() > 0) {
            byte[] h2 = readWindow(next, PTS_WINDOW_BYTES, false);
            nextFirst = com.github.tvbox.osc.util.TsPtsProbe.firstPtsSeconds(h2, h2.length);
        }
        return com.github.tvbox.osc.util.TsPtsProbe.continuityProblem(prevLast, subFirst, subLast, nextFirst);
    }

    /** 读文件头部/尾部窗口(尾部向前对齐到 188 包边界,保证 PTS 扫描能整包对齐) */
    private static byte[] readWindow(File f, int maxBytes, boolean tail) {
        try (FileInputStream in = new FileInputStream(f)) {
            long len = f.length();
            if (len <= 0) return new byte[0];
            int want = (int) Math.min(len, maxBytes);
            long start = 0;
            if (tail) {
                start = len - want;
                start -= start % com.github.tvbox.osc.util.TsProbe.TS_PACKET_SIZE; // 对齐到包边界
                want = (int) Math.min(len - start, maxBytes);
            }
            if (start > 0) {
                long skipped = in.skip(start);
                if (skipped < start) return new byte[0];
            }
            byte[] buf = new byte[want];
            int read = 0;
            while (read < want) {
                int n = in.read(buf, read, want - read);
                if (n <= 0) break;
                read += n;
            }
            return read == want ? buf : java.util.Arrays.copyOf(buf, read);
        } catch (Throwable th) {
            return new byte[0];
        }
    }

    /**
     * 只拉播放列表文本,不修改任务字段(备用线路探测用);返回 [文本, 实际使用的播放列表地址]。
     *
     * @param extraHeaders 该线路自己的请求头(可 null;每条线路的 Referer 可能不同)
     */
    private String[] fetchPlaylistReadOnly(String url, DownloadTask t, Map<String, String> extraHeaders)
            throws IOException {
        Map<String, String> headers = baseHeaders(t);
        if (extraHeaders != null && !extraHeaders.isEmpty()) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) headers.put(e.getKey(), e.getValue());
            }
        }
        Response resp = getDownloadResponse(url, headers);
        dm.activeResponses.put(t.id, resp);
        try {
            if (!resp.isSuccessful())
                throw new IOException("m3u8 HTTP " + resp.code());
            String text = resp.body().string();
            if (text.contains("#EXT-X-STREAM-INF")) {
                String base = url.substring(0, url.lastIndexOf('/') + 1);
                for (String line : text.split("\n")) {
                    String l = line.trim();
                    if (l.isEmpty() || l.startsWith("#"))
                        continue;
                    String variant = resolveUrl(url, base, l);
                    Response resp2 = getDownloadResponse(variant, headers);
                    dm.activeResponses.put(t.id, resp2);
                    try {
                        if (!resp2.isSuccessful())
                            throw new IOException("variant HTTP " + resp2.code());
                        return new String[] { resp2.body().string(), variant };
                    } finally {
                        dm.activeResponses.remove(t.id);
                        resp2.close();
                    }
                }
                throw new IOException("主播放列表无变体");
            }
            return new String[] { text, url };
        } finally {
            dm.activeResponses.remove(t.id);
            resp.close();
        }
    }

    /** 播放列表所在目录(拼相对地址用;取不到返回空串) */
    private static String dirOfUrl(String url) {
        if (url == null) return "";
        int i = url.lastIndexOf('/');
        return i > 0 ? url.substring(0, i + 1) : "";
    }

    /** 数一份 m3u8 里的分片行数(非空且非注释),用于统计净化剔除了几片 */
    private static int countSegmentLines(String playlist) {
        if (playlist == null || playlist.isEmpty()) return 0;
        int n = 0;
        for (String line : playlist.split("\r?\n")) {
            String l = line.trim();
            if (!l.isEmpty() && l.charAt(0) != '#') n++;
        }
        return n;
    }

    private String fetchPlaylist(String url, DownloadTask t) throws IOException {
        Response resp = getDownloadResponse(url, baseHeaders(t));
        dm.activeResponses.put(t.id, resp);
        try {
            if (!resp.isSuccessful())
                throw new IOException("m3u8 HTTP " + resp.code());
            String text = resp.body().string();
            // 主播放列表(多码率):取第一个变体
            if (text.contains("#EXT-X-STREAM-INF")) {
                String base = url.substring(0, url.lastIndexOf('/') + 1);
                for (String line : text.split("\n")) {
                    String l = line.trim();
                    if (l.isEmpty() || l.startsWith("#"))
                        continue;
                    String variant = resolveUrl(url, base, l);
                    Response resp2 = getDownloadResponse(variant, baseHeaders(t));
                    dm.activeResponses.put(t.id, resp2);
                    try {
                        if (!resp2.isSuccessful())
                            throw new IOException("variant HTTP " + resp2.code());
                        t.url = variant;
                        return resp2.body().string();
                    } finally {
                        dm.activeResponses.remove(t.id);
                        resp2.close();
                    }
                }
                throw new IOException("主播放列表无变体");
            }
            return text;
        } finally {
            dm.activeResponses.remove(t.id);
            resp.close();
        }
    }

    /**
     * 解析媒体播放列表的分片地址;keysOut 非空时按分片顺序输出解密密钥(无加密的分片为 null)。
     * 支持 #EXT-X-KEY:METHOD=AES-128(含播放列表内多 KEY 轮换);其余加密方法(如 SAMPLE-AES)明确报错。
     */
    private List<String> parseSegments(String playlistUrl, String playlist, List<HlsKey> keysOut) throws IOException {
        List<String> segs = new ArrayList<>();
        String base = playlistUrl.substring(0, playlistUrl.lastIndexOf('/') + 1);
        long mediaSeq = 0;
        String curKeyUri = null;
        byte[] curIv = null;
        for (String line : playlist.split("\n")) {
            String l = line.trim();
            if (l.isEmpty())
                continue;
            if (l.startsWith("#")) {
                if (l.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                    try {
                        mediaSeq = Long.parseLong(l.substring("#EXT-X-MEDIA-SEQUENCE:".length()).trim());
                    } catch (NumberFormatException ignored) {
                    }
                } else if (l.startsWith("#EXT-X-KEY:")) {
                    String method = attrValue(l, "METHOD");
                    if (method == null || "NONE".equalsIgnoreCase(method)) {
                        curKeyUri = null;
                        curIv = null;
                    } else if ("AES-128".equalsIgnoreCase(method)) {
                        String uri = attrValue(l, "URI");
                        if (uri == null || uri.isEmpty())
                            throw new IOException("EXT-X-KEY 缺少 URI");
                        curKeyUri = resolveUrl(playlistUrl, base, uri);
                        curIv = parseHexIv(attrValue(l, "IV"));
                    } else {
                        throw new IOException("暂不支持的 HLS 加密方式: " + method);
                    }
                } else if (l.startsWith("#EXT-X-MAP:") || l.startsWith("#EXT-X-BYTERANGE:")) {
                    // fMP4(#EXT-X-MAP 初始化段)/字节范围分片:只按整文件取分片会把 init 段丢掉
                    // (产物缺 moov,不可播)或取到错位数据。宁可明确失败,也不要悄悄产出坏文件。
                    throw new IOException("暂不支持 " + l.substring(0, l.indexOf(':') + 1)
                            + " 类型的 HLS(fMP4 初始化段/字节范围分片)");
                }
                continue;
            }
            // 代理返回的 m3u8 可能被 HTML 包裹(如 <pre>...</pre>): 含标签的行不是分片, 跳过
            if (l.contains("<") || l.contains(">"))
                continue;
            segs.add(resolveUrl(playlistUrl, base, l));
            if (keysOut != null) {
                if (curKeyUri == null) {
                    keysOut.add(null);
                } else {
                    // 无显式 IV 时按 HLS 规范用分片媒体序列号(16字节大端)
                    keysOut.add(new HlsKey(curKeyUri, curIv != null ? curIv : seqIv(mediaSeq)));
                }
            }
            mediaSeq++;
        }
        return segs;
    }

    /** HLS 分片 AES-128 解密信息:密钥地址 + IV(显式属性或由媒体序列号推导) */
    static final class HlsKey {
        final String keyUri;
        final byte[] iv;

        HlsKey(String keyUri, byte[] iv) {
            this.keyUri = keyUri;
            this.iv = iv;
        }
    }

    /** 拉取 HLS 密钥(16字节,AES-128);按 keyUri 缓存,同一播放列表多分片只取一次 */
    private byte[] loadKeyBytes(HlsKey key, DownloadTask t, Map<String, byte[]> cache) throws IOException {
        byte[] cached = cache.get(key.keyUri);
        if (cached != null)
            return cached;
        Response resp = getDownloadResponse(key.keyUri, baseHeaders(t));
        // 与其它请求一致登记,使暂停/删除/切网可中断在途的密钥请求(否则要等到读超时)。
        // 注意:调用点仍在读分段响应(它已占用 t.id 这个登记位),这里先顶替、finally 再还原,
        // 避免把分段响应一起摘掉导致后续暂停中断不到。
        Response replaced = dm.activeResponses.put(t.id, resp);
        try {
            if (!resp.isSuccessful())
                throw new IOException("HLS key HTTP " + resp.code());
            byte[] data = resp.body().bytes();
            if (data.length != 16)
                throw new IOException("HLS key 长度异常: " + data.length + "B");
            cache.put(key.keyUri, data);
            return data;
        } finally {
            if (replaced != null) {
                dm.activeResponses.put(t.id, replaced); // 还原分段响应的登记
            } else {
                dm.activeResponses.remove(t.id);
            }
            resp.close();
        }
    }

    /** 解析 HLS 标签属性值:支持带引号(URI="...")与不带引号(METHOD=AES-128) */
    private static String attrValue(String line, String name) {
        Matcher m = Pattern.compile(name + "=(\"[^\"]*\"|[^,]*)").matcher(line);
        if (!m.find())
            return null;
        String v = m.group(1);
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        return v;
    }

    /**
     * 解析 IV=0x<hex> 为 16 字节;格式非法返回 null(调用方回退媒体序列号)。
     * <p>
     * 允许短写(如 {@code IV=0x1}):HLS 的 IV 是 128 位整数,写成不足 32 位十六进制很常见,
     * 原来要求"必须 32 位"会把短写丢弃 → 回退成按媒体序列号推导的 IV → 解出来是乱码。
     */
    private static byte[] parseHexIv(String s) {
        if (s == null)
            return null;
        String hex = s.trim().toLowerCase(Locale.ROOT);
        if (hex.startsWith("0x"))
            hex = hex.substring(2);
        if (hex.isEmpty() || hex.length() > 32 || !hex.matches("[0-9a-f]+"))
            return null;
        while (hex.length() < 32) {
            hex = "0" + hex;
        }
        try {
            byte[] iv = new byte[16];
            for (int i = 0; i < 16; i++) {
                iv[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            }
            return iv;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** HLS 规范默认 IV:分片媒体序列号的 16 字节大端表示 */
    private static byte[] seqIv(long seq) {
        byte[] iv = new byte[16];
        for (int i = 15; i >= 8; i--) {
            iv[i] = (byte) (seq & 0xFF);
            seq >>= 8;
        }
        return iv;
    }

    /**
     * 分片地址补全:绝对地址原样返回;协议相对地址({@code //host/x})补 scheme;
     * 站内绝对路径({@code /x})用 {@code authority}(host[:port])拼 —— 注意必须带端口,
     * 原实现用 {@code getHost()} 丢端口,带端口的源(如 {@code :8080})分片会全部 404。
     */
    private String resolveUrl(String original, String base, String seg) {
        if (seg.startsWith("http://") || seg.startsWith("https://"))
            return seg;
        Uri uri = Uri.parse(original);
        String scheme = uri.getScheme() == null ? "http" : uri.getScheme();
        if (seg.startsWith("//")) {
            return scheme + ":" + seg;
        }
        if (seg.startsWith("/")) {
            String authority = uri.getAuthority();
            if (authority == null || authority.isEmpty())
                authority = uri.getHost();
            return joinUrl(scheme, authority, seg);
        }
        return base + seg;
    }

    /** 纯字符串拼接(便于单测):{@code scheme + "://" + authority + path} */
    static String joinUrl(String scheme, String authority, String path) {
        return scheme + "://" + authority + path;
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /**
     * 请求头:默认 ExoPlayer 同款 UA(播放器未自定义 UA 时用 ExoPlayerLib 默认, 防盗链代理
     * 放行它——okhttp/浏览器/系统 Dalvik UA 都返回 ASCII art 提示页) + 任务携带的解析请求头
     */
    Map<String, String> baseHeaders(DownloadTask t) {
        Map<String, String> headers = new HashMap<>();
        // 与播放器(ExoPlayer 2.18.7 默认 UA)一致: ExoPlayerLib/版本 (Linux;Android 版本)
        headers.put("User-Agent", "ExoPlayerLib/2.18.7 (Linux;Android "
                + android.os.Build.VERSION.RELEASE + ")");
        if (t != null && t.headers != null && !t.headers.isEmpty()) {
            for (Map.Entry<String, String> e : t.headers.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    headers.put(e.getKey(), e.getValue());
                }
            }
        }
        return headers;
    }

    /** 任务的分段下载目录:优先任务自己的 tmpDir,否则按 文件目录/tmp/<任务id> 兜底(保证唯一,多任务不共用) */
    File segmentsDirOf(DownloadTask t) {
        if (t.tmpDir != null && !t.tmpDir.isEmpty()) {
            return new File(t.tmpDir);
        }
        File parent = t.savePath != null ? new File(t.savePath).getParentFile() : null;
        String id = t.id != null && !t.id.isEmpty() ? t.id : "x";
        return new File(parent, "tmp" + File.separator + id);
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
            return dm.downloadClient.newCall(builder.build()).execute();
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
        synchronized (t) {
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
            boolean hlsUrl = t.url != null && t.url.toLowerCase().contains(".m3u8");
            if (hlsUrl) {
                t.estimatedBytes = estimateHlsBytes(t);
            } else {
                long exact = probeDirectBytes(t);
                if (exact > 0)
                    t.totalBytes = exact;
            }
        } catch (Throwable th) {
            Log.i("TVBox-Download", "大小探测失败: " + (t.fileName == null ? "?" : t.fileName) + " "
                    + (th.getMessage() == null ? th.toString() : th.getMessage()));
        } finally {
            synchronized (t) {
                t.probing = false;
                t.probeDone = true; // 成功/失败都记录,避免后续每次启动/重试重复探测
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

    /** m3u8 集大小估算:主播放列表带 BANDWIDTH 时按"总时长 × 码率",否则退化"分片数 × 2MB";失败返回 0 */
    private long estimateHlsBytes(DownloadTask t) {
        try {
            String url = t.url;
            String text = fetchText(url, t);
            if (text == null)
                return 0;
            long bandwidth = 0;
            if (text.contains("#EXT-X-STREAM-INF")) {
                // 主播放列表(多码率):与 fetchPlaylist 一致选第一个变体,并读取其 BANDWIDTH
                String base = url.substring(0, url.lastIndexOf('/') + 1);
                boolean pending = false;
                for (String raw : text.split("\n")) {
                    String line = raw.trim();
                    if (line.startsWith("#EXT-X-STREAM-INF")) {
                        Matcher m = Pattern.compile("BANDWIDTH=(\\d+)").matcher(line);
                        if (m.find()) {
                            try {
                                bandwidth = Long.parseLong(m.group(1));
                            } catch (NumberFormatException ignored) {
                            }
                        }
                        pending = true;
                    } else if (pending && !line.isEmpty() && !line.startsWith("#")) {
                        text = fetchText(resolveUrl(url, base, line), t);
                        if (text == null)
                            return 0;
                        pending = false;
                        break;
                    }
                }
                if (pending)
                    return 0; // 主列表无有效变体,无法估算
            }
            // 分片数(非注释行)与总时长(EXTINF 累计)
            int segs = 0;
            double durationSec = 0;
            Matcher dur = Pattern.compile("#EXTINF:\\s*([0-9]+(?:\\.[0-9]+)?)").matcher(text);
            while (dur.find()) {
                try {
                    durationSec += Double.parseDouble(dur.group(1));
                } catch (NumberFormatException ignored) {
                }
            }
            for (String line : text.split("\n")) {
                String l = line.trim();
                if (!l.isEmpty() && !l.startsWith("#") && !l.contains("<") && !l.contains(">"))
                    segs++;
            }
            if (segs <= 0)
                return 0;
            if (bandwidth > 0) {
                double sec = durationSec > 0 ? durationSec : segs * 6.0; // 无 EXTINF 时按每片 6s 兜底
                return Math.max(1L, (long) (sec * bandwidth / 8.0));
            }
            return segs * 2L * 1024 * 1024;
        } catch (Throwable th) {
            return 0;
        }
    }

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
    private boolean isM3u8Response(Response resp) {
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
    private boolean isHtmlResponse(Response resp) {
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
    private String detectExtensionFromResponse(Response resp) {
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
    private boolean isPlausibleVideo(byte[] head, String fileName) {
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
        byte[] needle = s.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        outer: for (int i = 0; i + needle.length <= head.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (head[i + j] != needle[j])
                    continue outer;
            }
            return true;
        }
        return false;
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
     * 500ms 窗口滑动节流; 限速不会改变行为, 只是放慢写入。
     */
    private static void throttle(DownloadTask t, int written) {
        if (t.speedLimit <= 0)
            return;
        long windowBytes = written;
        long windowStart = System.currentTimeMillis();
        while (t.speedLimit > 0 && !isInterrupted(t)) {
            long now = System.currentTimeMillis();
            long elapsed = now - windowStart;
            if (elapsed < 500)
                return; // 窗口未满, 继续
            long expect = t.speedLimit * elapsed / 1000;
            if (windowBytes <= expect)
                return; // 未超速
            long over = windowBytes - expect;
            long delay = over * 1000 / Math.max(1, t.speedLimit);
            try {
                Thread.sleep(Math.min(delay, 2000));
            } catch (InterruptedException ignored) {
                return;
            }
            windowStart = now;
            windowBytes = 0;
        }
    }

    /** 任务被暂停(用户/调度/网络)或已取消(删除记录)时,下载循环应中止 */
    private static boolean isInterrupted(DownloadTask t) {
        return t.state == DownloadTask.STATE_PAUSED
                || t.state == DownloadTask.STATE_SYSTEM_PAUSED
                || t.state == DownloadTask.STATE_NETWORK_PAUSED
                || t.state == DownloadTask.STATE_CANCELLED;
    }

    /**
     * Bug3: 把 TS 拼接产物重封装为标准 MP4（MediaExtractor demux + MediaMuxer mux）。
     * Android MediaExtractor 支持 demux MPEG-TS，mux 出的 MP4 时长/缩略图正确（无需 ffmpeg）。
     *
     * @return true=重封装成功并已替换源文件;false=失败(调用方回退 .ts 后缀)
     */
    private static boolean remuxTsToMp4(File src) {
        MediaExtractor extractor = null;
        MediaMuxer muxer = null;
        File outTmp = null;
        File repacked = null;
        boolean done = false;
        try {
            // 先自检产物结构:拼接产物可能是 188(标准)/192(M2TS)/204(FEC)字节包 ——
            // 后两种 Exo/IJK 能直接播,但 MediaExtractor 只按 188 对齐嗅探,拿它去 setDataSource
            // 会直接建不出提取器(NuMediaExtractor: failed to create MediaExtractor /
            // Failed to instantiate extractor),重封装因此失败、只能回退 .ts。
            // 识别出来先重打包成标准 188 再交给它;认不出来仍原样尝试(失败照旧回退 .ts)。
            TsProbe probe = probeHead(src);
            Log.i("TVBox-Download", "重封装自检: " + src.getName() + " -> " + probe.describe());
            File remuxSrc = src;
            if (probe.needsRepack()) {
                repacked = new File(src.getParentFile(), "repack_" + System.currentTimeMillis() + ".ts");
                if (repackTo188(src, repacked, probe)) {
                    remuxSrc = repacked;
                    Log.i("TVBox-Download", "重封装:已重打包为 188 字节包 " + formatSize(repacked.length()));
                } else {
                    FileCleaner.deleteQuietly(repacked);
                    repacked = null;
                }
            }
            extractor = openExtractor(remuxSrc);
            int videoTrack = -1;
            int audioTrack = -1;
            MediaFormat videoFormat = null;
            MediaFormat audioFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime == null)
                    continue;
                if (mime.startsWith("video/") && videoTrack < 0) {
                    videoTrack = i;
                    videoFormat = f;
                } else if (mime.startsWith("audio/") && audioTrack < 0) {
                    audioTrack = i;
                    audioFormat = f;
                }
            }
            if (videoTrack < 0 && audioTrack < 0)
                return false; // 无可用轨,无法重封装

            outTmp = new File(src.getParentFile(), "remux_" + System.currentTimeMillis() + ".mp4");
            muxer = new MediaMuxer(outTmp.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int muxVideo = -1;
            int muxAudio = -1;
            if (videoTrack >= 0) {
                extractor.selectTrack(videoTrack);
                muxVideo = muxer.addTrack(videoFormat);
            }
            if (audioTrack >= 0) {
                extractor.selectTrack(audioTrack);
                muxAudio = muxer.addTrack(audioFormat);
            }
            muxer.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            // 8MB 起步:高码率关键帧可超 1MB,缓冲不足时倍增重试同一样本(不丢帧),64MB 封顶
            ByteBuffer buffer = ByteBuffer.allocate(8 * 1024 * 1024);
            // 多段 TS 字节拼接后段间 PTS 可能回跳/重置,而 MediaMuxer 要求每轨单调不减,
            // 回退即抛 IllegalArgumentException 导致整个重封装失败——按轨钳制到 lastPts+1
            long lastVideoPts = Long.MIN_VALUE;
            long lastAudioPts = Long.MIN_VALUE;
            while (true) {
                int track = extractor.getSampleTrackIndex();
                if (track < 0)
                    break;
                int size = extractor.readSampleData(buffer, 0);
                if (size < 0) {
                    // 缓冲区不足:扩容后重读同一样本(advance 前可重复调用);已封顶则跳过该样本
                    if (buffer.capacity() >= 64 * 1024 * 1024) {
                        extractor.advance();
                        continue;
                    }
                    buffer = ByteBuffer.allocate(buffer.capacity() * 2);
                    continue;
                }
                if (size == 0) {
                    extractor.advance();
                    continue;
                }
                long pts = extractor.getSampleTime();
                int flags = extractor.getSampleFlags();
                if (track == videoTrack) {
                    if (lastVideoPts != Long.MIN_VALUE && pts <= lastVideoPts)
                        pts = lastVideoPts + 1;
                    lastVideoPts = pts;
                } else if (track == audioTrack) {
                    if (lastAudioPts != Long.MIN_VALUE && pts <= lastAudioPts)
                        pts = lastAudioPts + 1;
                    lastAudioPts = pts;
                }
                buffer.position(0);
                buffer.limit(size);
                info.offset = 0;
                info.size = size;
                info.presentationTimeUs = pts;
                info.flags = flags;
                if (track == videoTrack && muxVideo >= 0) {
                    muxer.writeSampleData(muxVideo, buffer, info);
                } else if (track == audioTrack && muxAudio >= 0) {
                    muxer.writeSampleData(muxAudio, buffer, info);
                }
                extractor.advance();
            }
            muxer.stop();
            muxer.release();
            muxer = null;
            extractor.release();
            extractor = null;
            // 用重封装产物替换原拼接文件
            if (!outTmp.renameTo(src)) {
                if (src.exists()) {
                    // noinspection ResultOfMethodCallIgnored
                    src.delete();
                }
                if (!outTmp.renameTo(src)) {
                    FileCleaner.copyFile(outTmp, src);
                    FileCleaner.deleteQuietly(outTmp);
                }
            }
            done = true;
            return true;
        } catch (Throwable th) {
            // 带异常类型与绝对路径落日志(getMessage 对部分系统异常为 null;路径能区分公共/应用私有下载目录,便于定位)
            Log.w("TVBox-Download", "重封装失败: " + src.getAbsolutePath() + " -> " + th, th);
            return false;
        } finally {
            if (muxer != null) {
                try {
                    muxer.release();
                } catch (Throwable ignored) {
                }
            }
            if (extractor != null) {
                try {
                    extractor.release();
                } catch (Throwable ignored) {
                }
            }
            // 失败/中途异常时清掉半成品 remux_*.mp4:以前会留在下载目录里当垃圾文件(几十~几百MB)
            if (!done && outTmp != null) {
                FileCleaner.deleteQuietly(outTmp);
            }
            // 192/204 -> 188 的重打包临时件(成品已由 outTmp 覆盖回 src,它只是中间产物)
            if (repacked != null) {
                FileCleaner.deleteQuietly(repacked);
            }
        }
    }

    /** 读文件头做结构自检(4KB 足够验多个包的同步字节) */
    private static TsProbe probeHead(File f) {
        try (InputStream is = new FileInputStream(f)) {
            byte[] buf = new byte[4096];
            int n = is.read(buf);
            if (n <= 0) return TsProbe.of(null);
            return TsProbe.of(n == buf.length ? buf : java.util.Arrays.copyOf(buf, n));
        } catch (Throwable th) {
            Log.w("TVBox-Download", "重封装自检失败: " + f.getAbsolutePath() + " -> " + th);
            return TsProbe.of(null);
        }
    }

    /**
     * 把 192/204 字节包逐包剥成标准 188 字节包(丢掉每包多出来的时间戳/FEC 字节)。
     * 逐块读满再转,避免短读把包边界切错;末尾不足一包的残字节丢弃(拼接产物末尾本就是整包)。
     */
    private static boolean repackTo188(File src, File dst, TsProbe probe) {
        int ps = probe.packetSize;
        byte[] buf = new byte[ps * 4096];
        long written = 0;
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            int fill = 0;
            int n;
            while ((n = in.read(buf, fill, buf.length - fill)) > 0) {
                fill += n;
                if (fill < buf.length) continue; // 攒满一整块再处理
                byte[] packed = TsProbe.repackUnit(buf, fill, probe);
                if (packed == null) return false;
                out.write(packed);
                written += packed.length;
                fill = 0;
            }
            int usable = fill - (fill % ps); // 末尾残块只取整包
            if (usable > 0) {
                byte[] packed = TsProbe.repackUnit(buf, usable, probe);
                if (packed == null) return false;
                out.write(packed);
                written += packed.length;
            }
            out.flush();
            return written > 0;
        } catch (Throwable th) {
            Log.w("TVBox-Download", "重封装:重打包失败 -> " + th);
            return false;
        }
    }

    /**
     * 打开提取器并 setDataSource,失败重试一次(媒体服务偶发创建失败/提取器实例受限时,隔 300ms 能过去)。
     * <p>
     * 打开方式必须**优先用 FileDescriptor**:{@link MediaExtractor#setDataSource(String)} 的文档写明
     * "本地文件可能由**别的进程**(媒体服务)按路径打开,该路径必须是 world-readable 的";
     * 而下载目录要么在应用私有 files 下,要么在 Android/data/&lt;包名&gt; 下(见 FileCleaner.getSaveDir),
     * 媒体服务读不到(EACCES),服务侧创建提取器即失败 —— MTK/魅族机型的实测日志正是这一串:
     * {@code Parcel: Expecting binder but got null!} → {@code NuMediaExtractor: initMediaExtractor:
     * failed to create MediaExtractor} → {@code IOException: Failed to instantiate extractor}(栈顶落在
     * MediaExtractor.java:202,即传路径的那个重载)。FD 形式由本进程开文件、把 FD 通过 binder 交给提取器,
     * 既不受私有目录限制,也不受中文路径影响(文档同时说明:本调用返回后即可关闭 FD,框架已 dup)。
     * <p>
     * 路径形式保留为兜底(公共 Download 目录 + 部分 ROM 只有路径形式可用),两次都失败则抛出最后一次异常,
     * 由调用方回退 .ts 后缀。
     */
    private static MediaExtractor openExtractor(File src) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return openExtractorOnce(src);
            } catch (IOException e) {
                last = e;
                Log.w("TVBox-Download", "重封装:第 " + attempt + " 次打开提取器失败 -> " + e);
                if (attempt < 2) {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        throw last != null ? last : new IOException("Failed to instantiate extractor");
    }

    /** 单次尝试:FD 形式优先,失败退回路径形式 */
    private static MediaExtractor openExtractorOnce(File src) throws IOException {
        MediaExtractor byFd = new MediaExtractor();
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(src);
            byFd.setDataSource(fis.getFD());
            return byFd;
        } catch (Throwable fdFail) {
            try {
                byFd.release();
            } catch (Throwable ignored) {
            }
            Log.w("TVBox-Download", "重封装:FD 形式打开失败,退回路径形式: " + src.getAbsolutePath() + " -> " + fdFail);
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (Throwable ignored) {
                }
            }
        }
        MediaExtractor byPath = new MediaExtractor();
        byPath.setDataSource(src.getAbsolutePath());
        return byPath;
    }

    /** Bug5: 确保目录内写入 .nomedia(媒体扫描器忽略该目录,碎片不进相册) */
    private static void ensureNoMedia(File dir) {
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
    private static String formatSize(long bytes) {
        if (bytes < 1024 * 1024)
            return String.format("%.0fKB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024)
            return String.format("%.1fMB", bytes / 1024.0 / 1024.0);
        return String.format("%.2fGB", bytes / 1024.0 / 1024.0 / 1024.0);
    }

    /** 缺失清单转可读串 "[1,3,7]"; 空清单返回 "[]" (日志全量落清单, 不截断) */
    private static String missingList(List<Integer> missing) {
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
