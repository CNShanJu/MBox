package com.github.tvbox.osc.download.internal;

import android.media.MediaExtractor;
import android.media.MediaMuxer;
import android.net.Uri;
import android.util.Log;

import com.github.tvbox.osc.bean.DownloadTask;
import com.github.tvbox.osc.util.PlaylistSnapshot;
import com.github.tvbox.osc.download.DownloadSubType;
import com.github.tvbox.osc.state.StorageSpace;
import com.github.tvbox.osc.util.HlsMediaPlaylist;
import com.github.tvbox.osc.util.SegmentUnwrapper;
import com.github.tvbox.osc.util.TsProbe;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Response;

class HlsDownloader {
    private final DownloadManager dm;
    private final DownloadExecutor executor;
    HlsDownloader(DownloadManager dm, DownloadExecutor executor) { this.dm = dm; this.executor = executor; }
    private static final int MAX_CONSECUTIVE_SEGMENT_FAIL = 8;
    private static final long REPAIR_ROUND_DELAY_MS = 1500L;
    private static final String SEGMENTS_SIG = "segments.sig";
    private static final String INIT_SEGMENT_FILE = "init.mp4";
    private static final int HEAD_PROBE_BYTES = 8192;
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)");
    void download(DownloadTask t) throws IOException {
        String playlistUrl = t.url;
        String playlist = fetchPlaylist(playlistUrl, t);
        // 与播放链路用同一份净化规则(:common 的 M3u8Purifier,播放侧 PlayFragment 也是它):
        // ① 剔掉少数派分片(广告/占位)—— 这些片源站往往已经删了,播放器因为不看它们所以一路顺,
        //    下载器照单全下就会"播放不缺、下载缺"(实测 940 片里 8 片 404 全是少数派前缀);
        // ② 把清单内相对地址补成绝对地址(下载侧同样按播放列表目录解析,顺手统一)。
        // 返回 null = 无法判定(前缀分组过多/结构异常),与播放侧一样按"不净化"处理。
        String purified = purifyForDownload(t.url, playlist);
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
        HlsPlaylistData pl = parsePlaylist(t.url, playlist);
        List<String> segments = pl.urls;
        List<HlsMediaPlaylist.ByteRange> segRanges = pl.ranges;
        List<HlsKey> segKeys = pl.keys;
        HlsMediaPlaylist.InitSegment initSeg = pl.init;
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
        // fMP4(有 init 段)与字节范围分片要留痕:取片方式、产物形态都与普通 TS 清单不同,排障第一眼就得看出来
        boolean fmp4 = initSeg != null;
        boolean ranged = pl.hasRanges();
        StringBuilder layout = new StringBuilder();
        if (fmp4) layout.append("fMP4, init=").append(initSeg);
        if (ranged) layout.append(layout.length() > 0 ? ", " : "").append("字节范围分片");
        Log.i("TVBox-Download", "播放列表 " + segments.size() + " 片" + (encrypted ? "(AES-128 加密,分片解密后落盘)" : "")
                + (layout.length() > 0 ? "[" + layout + "]" : "")
                + ", 播放列表url=" + t.url + " 首片=" + segments.get(0));
        if (fmp4) {
            DownloadLog.LOG.info(DownloadSubType.PLAYLIST, "fMP4 清单: init 段 " + initSeg
                    + "(合并时先写 init 段再按序号拼分片)", DownloadLog.extras(t.episodeId));
        }
        t.totalSegments = segments.size();
        // 续传起点以磁盘实况为准(不信任 TXT/内存计数):用户可能删过部分分片文件,
        // 若仍用 t.doneSegments 会跳过缺失分片直接合并导致失败。
        File tmpDir = executor.segmentsDirOf(t);
        if (!tmpDir.exists())
            tmpDir.mkdirs();
        DownloadExecutor.ensureNoMedia(tmpDir); // Bug5: 碎片目录放 .nomedia,防止 TS 碎片进系统相册
        int existing = countExistingSegments(tmpDir, segments.size());
        if (existing < t.doneSegments) {
            Log.i("TVBox-Download", "分片缺失(磁盘" + existing + "/" + segments.size() + ",记录" + t.doneSegments
                    + "),从缺失处续传: " + t.fileName);
        }
        t.doneSegments = existing;
        t.segmentBytes = 0;
        // 换线路保护:重新解析地址后如果分片列表变了,旧碎片属于另一份播放列表,必须丢弃重下
        // (否则两份视频的碎片会被合并成一个放不了的文件);列表一致则保留进度续传
        dropSegmentsIfPlaylistChanged(t, tmpDir, segments, segRanges, pl.snapshot);
        // 下载前记录分段信息 TXT:来源/剧名/集数/碎片数/解析地址/分片列表/已完成(断点续传同步进度)
        writeSegmentsInfo(t, tmpDir, segments, t.doneSegments);

        Map<String, byte[]> keyCache = new HashMap<>();
        HlsSessionRunner session = new HlsSessionRunner(() -> refreshHlsSession(t, pl, keyCache),
                () -> DownloadExecutor.isInterrupted(t), millis -> {
                    try { Thread.sleep(millis); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("任务已停止", e); }
                });

        // fMP4 的 init 段先下:它是所有分片的前置数据(ftyp/moov),缺了整集产物都解不出来 ——
        // 一个小请求换"失败早知道"(等几百片下完再发现 init 拿不到,白白浪费整集流量)。
        // 落 init.mp4,不占分片序号；恢复时验证其内容仍相同。
        File initFile = null;
        if (fmp4) {
            initFile = new File(tmpDir, INIT_SEGMENT_FILE);
            final File target = initFile;
            session.run(-1, () -> verifyOrDownloadInit(pl.init, target, t));
                if (DownloadExecutor.isInterrupted(t)) {
                    t.speed = 0;
                    dm.persist();
                    dm.notifyChanged();
                    return;
                }
        }

        long speedWindowStart = System.currentTimeMillis();
        long speedWindowBytes = 0;
        // 加密 HLS 的密钥缓存(按 keyUri 复用,整个任务只拉一次密钥)

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
            // 每片一个检查点:暂停(用户/调度/网络)或"存储空间见底(看门狗已暂停全部)"都立即收尾
            if (DownloadExecutor.isInterrupted(t) || dm.watchdog.checkWhileDownloading()) {
                t.speed = 0;
                dm.persist();
                dm.notifyChanged();
                return;
            }
            File segFile = new File(tmpDir, String.format("%05d.ts", i));
            if (segFile.exists() && segFile.length() > 0) {
                consecutiveFail = 0;
                consecutiveGone = 0;
                continue; // 已存在,跳过
            }
            try {
                final int index = i;
                session.run(index, () -> downloadSegment(index, segments.get(index), segFile, 0, t,
                        segKeys.get(index), keyCache, null, segRanges.get(index)));
                if (DownloadExecutor.isInterrupted(t)) {
                    // downloadSegment 遇中断是"正常 return"(不抛异常):这里必须同步收尾,
                    // 否则调用方会把这一片记成已完成(doneSegments 推进),进度与磁盘不一致
                    t.speed = 0;
                    dm.persist();
                    dm.notifyChanged();
                    return;
                }
                consecutiveFail = 0;
                consecutiveGone = 0;
            } catch (IOException e) {
                if (DownloadExecutor.isInterrupted(t)) {
                    // 本方暂停/取消导致的失败:与循环顶部的中断处理同语义,交给上层收尾
                    t.speed = 0;
                    dm.persist();
                    dm.notifyChanged();
                    return;
                }
                if (e instanceof DownloadErrors.SessionExpired || e instanceof DownloadErrors.LayoutChanged
                        || DownloadErrors.isStorageError(e)) throw e;
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
                DownloadLog.LOG.warn(DownloadSubType.FAIL, "分片失败/片 " + i + ": " + DownloadErrors.reasonOf(e)
                        + " url=" + segUrlForLog(segments.get(i)),
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
            t.doneSegments++;
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
                + " 项" + DownloadExecutor.missingList(missing), DownloadLog.extras(t.episodeId));
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
                    List<Integer> left = patchMissingFromAltRoutes(t, tmpDir, missing, primaryDurations, fmp4);
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
                            + DownloadManager.MAX_SEGMENT_REPAIR + " 轮未补齐") + ":" + DownloadExecutor.missingList(missing),
                            DownloadLog.extras(t.episodeId));
                    break;
                }
                // 补片 FAILED: 完整缺失清单落日志(不截断), 供事后核对; 保留碎片现场
                DownloadLog.LOG.fail(DownloadSubType.REPAIR, "补片 FAILED: " + (allGone ? "剩余缺片全是源侧永久失效" : "第 "
                        + DownloadManager.MAX_SEGMENT_REPAIR + " 轮仍缺失") + " " + missing.size() + " 片:"
                        + DownloadExecutor.missingList(missing), DownloadLog.extras(t.episodeId));
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
                    + " 轮开始: 目标 " + missing.size() + " 片" + DownloadExecutor.missingList(missing), DownloadLog.extras(t.episodeId));
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
                        session.run(idx, () -> downloadSegment(idx, segments.get(idx), segFile, 0, t,
                                segKeys.get(idx), keyCache, null, segRanges.get(idx)));
                        okCount++;
                    } catch (IOException e) {
                        if (DownloadExecutor.isInterrupted(t) || e instanceof DownloadErrors.SessionExpired
                                || e instanceof DownloadErrors.LayoutChanged || DownloadErrors.isStorageError(e)
                                || DownloadErrors.isNetworkError(e)) throw e;
                        // 单项补下失败: 原因+HTTP码(留待下轮);同时记下最后原因,供最终失败提示带上
                        failCount++;
                        lastSegErr = e;
                        if (DownloadErrors.isSegmentGone(e)) {
                            // 死片:记下来,后面几轮不再对它发请求(否则 3 轮 × 每轮一次,同一片白请求多次)
                            goneSegments.add(idx);
                        }
                        Log.i("TVBox-Download", "补片失败(留待下轮): 片" + idx + " " + e.getMessage());
                        DownloadLog.LOG.fail(DownloadSubType.REPAIR, "补片/片 " + idx + " 失败 " + e.getMessage()
                                + " url=" + segUrlForLog(segments.get(idx)),
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
                    + " 片" + DownloadExecutor.missingList(missing), DownloadLog.extras(t.episodeId));
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
            // 合并前空间检查:按 1× 成品大小预留(与 DownloadPolicy 同口径,不做峰值倍数)。
            // 合并产物/重封装产物确实会短暂同时存在,但真不够时是优雅退化:合并失败保留碎片可重试、
            // 重封装失败只是回退 .ts(成品仍可播),不值得为不确定的估算误伤用户。
            long mergeSize = 0;
            for (int i = 0; i < segments.size(); i++) {
                mergeSize += new File(tmpDir, String.format("%05d.ts", i)).length();
            }
            if (initFile != null && initFile.exists()) {
                mergeSize += initFile.length(); // fMP4 的 init 段也在成品里,空间预检不能漏算它
            }
            if (mergeSize > 0) {
                File dir = new File(t.savePath).getParentFile();
                if (dir != null && dir.exists()) {
                    long free = StorageSpace.freeBytes(dir);
                    if (free - mergeSize < DownloadPolicy.MIN_FREE_SPACE) {
                        throw new IOException("磁盘空间不足,无法合并(完成后可用仅 "
                                + DownloadExecutor.formatSize(Math.max(0, free - mergeSize)) + ",需清理约 "
                                + ((DownloadPolicy.MIN_FREE_SPACE - (free - mergeSize) + 1024 * 1024 - 1)
                                        / (1024 * 1024))
                                + "MB)");
                    }
                }
            }
            // 合并/开始 日志: 分片N, 缺失清单状态(已清空 / 缺片完成+片号), 分片总size, 目标路径
            DownloadLog.LOG.info(DownloadSubType.MERGE, "合并开始 第 " + t.mergeCount + " 次: 分片 " + segments.size()
                    + ", 缺失清单=" + (gapSegments.isEmpty() ? "已清空" : "缺片完成" + DownloadExecutor.missingList(gapSegments))
                    + ", 分片总size=" + DownloadExecutor.formatSize(mergeSize) + ", 目标 " + t.savePath,
                    DownloadLog.extras(t.episodeId));

            final long expectedMergeSize = mergeSize;
            HlsMerger.merge(tmpDir, segments.size(), initFile, mergeTmp, new HashSet<>(gapSegments),
                    () -> DownloadExecutor.isInterrupted(t), (bytes, index) -> {
                        if (dm.watchdog.checkWhileDownloading())
                            throw new IOException("存储空间不足，合并已中止（清理空间后继续）");
                        t.message = DownloadManager.MSG_MERGING + "(" + (bytes * 100 / Math.max(1L, expectedMergeSize)) + "%)";
                        dm.flushProgress(t);
                    });
            // 原子替换:先删旧最终文件(若有),再 rename;rename 失败则复制兜底
            if (finalFile.exists())
                finalFile.delete();
            if (!mergeTmp.renameTo(finalFile)) {
                FileCleaner.copyFile(mergeTmp, finalFile);
                FileCleaner.deleteQuietly(mergeTmp);
            }
            // 合并/完成 日志: 最终size, 耗时ms
            DownloadLog.LOG.success(DownloadSubType.MERGE, "合并完成: 最终 " + DownloadExecutor.formatSize(finalFile.length()) + ", 耗时 "
                    + (System.currentTimeMillis() - mergeStart) + "ms", DownloadLog.extras(t.episodeId));
            t.mergeFailReason = ""; // 合并成功,清空失败原因
        } catch (IOException e) {
            // 合并/失败 日志: 第k次, 原因(IO/缺片/校验不符), 碎片保留(不删 .ts, 供重试)
            t.mergeFailReason = e.getMessage() == null ? e.toString() : e.getMessage();
            DownloadLog.LOG.fail(DownloadSubType.MERGE, "合并失败 第 " + t.mergeCount + " 次, 原因: " + t.mergeFailReason
                    + ", 碎片保留", DownloadLog.extras(t.episodeId));
            throw new DownloadErrors.MergeFailure(t.mergeFailReason, e);
        }
        // 合并产物内容体检:既不是 TS 也不是 MP4 就不是音视频(常见:分片其实是图片/HTML 错误页,
        // 例如"整条线路返回 940 张 jpg")。这种绝不能算下载成功 —— 以前会"回退 .ts"照旧置 COMPLETED,
        // 用户拿到一个打不开的文件还不知道为什么。这里直接判失败,并把内容线索写进原因:
        // 调度器据此(见 shouldReResolve)自动重新解析地址换线路,提示里也能一眼看出是源的问题。
        TsProbe mergedProbe = MediaRemuxer.probeHead(finalFile);
        // "只有 fMP4 片段、缺 init 段"这条判据只对<b>没有</b> init 段的清单成立:我们已经把 init 段写在
        // 最前面,且它已过"含 moov、且在 moof/mdat 之前"的自检(见 downloadInitSegment),产物不可能是
        // "缺 moov 的裸片段" —— 不该因为某些源把 styp 也放进 init 段就误判成坏产物
        boolean fragmentedOnly = !fmp4 && mergedProbe.kind == TsProbe.Kind.MP4
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
        /** 重封装失败回退 .ts 时的完成说明(空=没回退);让用户直接看到这集为何是 TS 而不是 MP4 */
        String remuxNote = "";
        if (executor.remuxTsToMp4(t, finalFile)) {
            DownloadLog.LOG.success(DownloadSubType.REMUX, "重封装完成: " + t.fileName, DownloadLog.extras(t.episodeId));
        } else if (fmp4) {
            // fMP4 的拼接产物本身就是合法 MP4(init 段 ftyp/moov + 一串 moof/mdat),
            // 重封装只是锦上添花:失败就原样保留 .mp4 —— 把 .mp4 改名成 .ts 是 TS 字节流才需要的伪装,
            // 对 MP4 产物改名只会让后缀与内容不符(播放器/文件管理器都可能误判)
            DownloadLog.LOG.warn(DownloadSubType.REMUX,
                    "重封装失败,保留 .mp4 成品(fMP4 产物本身即合法 MP4,不改名伪装成 .ts): " + t.fileName,
                    DownloadLog.extras(t.episodeId));
        } else {
            if (t.savePath.toLowerCase(Locale.ROOT).endsWith(".mp4")) {
                String tsPath = t.savePath.substring(0, t.savePath.length() - 4) + ".ts";
                File tsFile = new File(tsPath);
                if (finalFile.renameTo(tsFile)) {
                    t.savePath = tsPath;
                    t.fileName = tsFile.getName();
                }
            }
            // 用户能看到"为什么这集是 ts 而不是 mp4":回退不是静默的,完成信息里带上原因
            remuxNote = "未封装为 MP4(保留 TS,原因见日志)";
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
        if (!remuxNote.isEmpty()) {
            doneNote = doneNote.isEmpty() ? remuxNote : doneNote + "," + remuxNote;
        }
        t.message = doneNote.isEmpty() ? "" : "已完成(" + doneNote + ")";
        t.state = DownloadTask.STATE_COMPLETED;
        DownloadLog.LOG.success(DownloadSubType.SAVE, "下载完成: " + t.fileName, DownloadLog.extras(t.episodeId));
        dm.archive.add(t); // 先写档案(长期)
        // 档案写成功后才清理分片目录(父级 tmp 保留)
        executor.deleteSegmentsDir(t);
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
     *
     * <p>指纹里带上字节范围({@code #EXT-X-BYTERANGE}):同一个大文件的不同区间是完全不同的内容,
     * 只看 URL 会把"另一份清单的区间"当成同一片复用,产物必坏。
     */
    private void dropSegmentsIfPlaylistChanged(DownloadTask t, File tmpDir, List<String> segments,
            List<HlsMediaPlaylist.ByteRange> ranges, PlaylistSnapshot snapshot) throws IOException {
        String sig = snapshot.layoutSignature;
        if (sig.isEmpty()) return;
        File sigFile = new File(tmpDir, SEGMENTS_SIG);
        try {
            if (!sigFile.exists() && countExistingSegments(tmpDir, segments.size()) == 0) {
                writeText(sigFile, sig);
                return;
            }
            String old = sigFile.exists() ? readText(sigFile).trim() : "";
            boolean legacySafe = snapshot.playlist.init == null;
            for (HlsMediaPlaylist.Segment segment : snapshot.playlist.segments) if (segment.key != null) legacySafe = false;
            if (sig.equals(old) || (legacySafe && old.equals(com.github.tvbox.osc.util.SegmentListSignature.of(segments, ranges)))) {
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
            // 整目录清碎片(含新旧数量不一致时多出来的尾巴);segments.txt/指纹/.nomedia 保留,随后会重写。
            // init.mp4 一并删:它是旧清单的 init 段,与新清单的 fMP4 分片配不上(残留会被合并成一个坏产物)
            File[] leftovers = tmpDir.listFiles();
            if (leftovers != null) {
                for (File f : leftovers) {
                    String n = f.getName();
                    if (n.endsWith(".ts") || n.endsWith(".ts.part") || n.startsWith(".keyhash-")
                            || n.equals(INIT_SEGMENT_FILE) || n.equals(INIT_SEGMENT_FILE + ".part")) {
                        FileCleaner.deleteQuietly(f);
                    }
                }
            }
            t.doneSegments = 0;
            t.segmentBytes = 0;
            writeText(sigFile, sig);
        } catch (Throwable th) {
            throw new IOException("无法校验分片布局，已停止以避免混片", th);
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
            sb.append("解析地址=").append(PlaylistSnapshot.resourceIdentity(t.url)).append('\n');
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
     * @param segIndex     分片序号(仅用于失败分类:404/410 抛 {@link DownloadErrors.SegmentGoneException} 时带上序号,
     *                     让上层记住这片是"死片",不再重复请求)
     * @param segDone      续传起点(已下字节数):走"从已下字节到资源末尾"的 <b>open-ended</b> Range
     *                     ({@code bytes=N-});与 {@code range} 的"<b>固定区间</b> Range"是两件不同的事,
     *                     二者互斥(见方法内第一段注释)
     * @param extraHeaders 额外请求头(覆盖任务自带的):跨线路补片时用备用线路解析出来的防盗链头
     *                     (每条线路可能各有各的 Referer),为 null 时只用任务自带的头
     * @param range        播放列表声明的固定字节范围({@code #EXT-X-BYTERANGE};null=整个资源就是这一片)。
     *                     <b>必须</b>用固定区间请求:不带范围会把整个大文件当一个分片存下来
     */
    private void downloadSegment(int segIndex, String segUrl, File segFile, long segDone, DownloadTask t,
            HlsKey key, Map<String, byte[]> keyCache, Map<String, String> extraHeaders,
            HlsMediaPlaylist.ByteRange range) throws IOException {
        // 固定区间分片一律整段重下:续传偏移 segDone 是"相对整个资源文件"的坐标,而这一片只占
        // [offset, offset+length) —— 两个坐标系的偏移混用会取到别的字节,产物必坏。残留 .part 一并丢掉。
        if (range != null && segDone != 0) {
            segDone = 0;
            FileCleaner.deleteQuietly(new File(segFile.getAbsolutePath() + ".part"));
        }
        // 加密分片无法断点续传(AES-CBC 需从头整段解密),一律整段下
        if (key != null)
            segDone = 0;
        // 上次这个分片是"包裹型"(已剥壳落盘):Range 偏移与原始字节对不上,必须整段重下
        File wrapMarker = new File(segFile.getAbsolutePath() + ".wrap");
        if (wrapMarker.exists()) {
            segDone = 0;
            FileCleaner.deleteQuietly(new File(segFile.getAbsolutePath() + ".part"));
        }
        Map<String, String> headers = executor.baseHeaders(t);
        if (extraHeaders != null && !extraHeaders.isEmpty()) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) headers.put(e.getKey(), e.getValue());
            }
        }
        if (range != null) {
            headers.put("Range", range.headerValue());
        } else if (segDone > 0) {
            headers.put("Range", "bytes=" + segDone + "-");
        }
        Response resp = executor.getDownloadResponse(segUrl, headers);
        dm.activeResponses.put(t.id, resp);
        try {
            int code = resp.code();
            if (range != null) {
                // 固定区间:只接受 206。服务器<strong>忽略</strong> Range 回了 200(整个资源)时必须判失败 ——
                // 否则会把整个大文件当成"这一片"存进 %05d.ts,产物必坏,而且后面每一片都会重复整文件。
                if (code == 200) {
                    throw new IOException("服务器忽略 Range 返回 200(整文件),无法按字节范围 "
                            + range.describe() + " 取分片");
                }
                if (code != 206) {
                    throw segmentHttpFailure(segIndex, segUrl, code);
                }
                String ct = resp.header("Content-Type");
                if (ct != null && ct.toLowerCase(Locale.ROOT).contains("multipart/byteranges")) {
                    // 服务器把我们的单区间请求当多区间处理:响应体是 MIME 包装而不是分片字节
                    throw new IOException("服务器按多区间响应 Range(multipart/byteranges),无法取单片");
                }
                long declaredRange = resp.body().contentLength();
                if (declaredRange > 0 && declaredRange != range.length) {
                    throw new IOException("区间长度与清单不符(清单 " + range.length + " B,服务器 "
                            + declaredRange + " B)");
                }
                String mismatch = contentRangeMismatch(resp.header("Content-Range"), range);
                if (mismatch != null) {
                    throw new IOException(mismatch);
                }
            } else if (code == 416) {
                // Range 超出文件末尾:该分段实际已完整(上次写入完成但进度未更新)。
                // 关闭本次响应,删除残片,不带 Range 从头整段重下,避免重试死循环
                Log.i("TVBox-Download", "分段416(Range超界),整段重下: " + segFile.getName());
                resp.close();
                dm.activeResponses.remove(t.id);
                FileCleaner.deleteQuietly(segFile);
                FileCleaner.deleteQuietly(new File(segFile.getAbsolutePath() + ".part"));
                segDone = 0;
                headers.remove("Range");
                resp = executor.getDownloadResponse(segUrl, headers);
                dm.activeResponses.put(t.id, resp);
                code = resp.code();
            }
            if (range == null && code == 200 && segDone > 0) {
                segDone = 0;
                FileCleaner.deleteQuietly(segFile);
            } else if (range == null && code != 200 && code != 206) {
                throw segmentHttpFailure(segIndex, segUrl, code);
            }
            File parent = segFile.getParentFile();
            if (parent != null && !parent.exists())
                parent.mkdirs();

            // 构建输入流:加密分片经 AES-128-CBC 边下边解密(明文落盘,续传/校验/合并/封装流程不变),
            // 非加密分片即原始字节流。密钥按 keyUri 缓存,整任务只拉一次。
            // 服务器声明的长度(未知为 -1):整段原样落盘时用它校验有没有被提前掐断(见下面的完整性校验)
            long declared = resp.body().contentLength();
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
            // 实际落盘字节(完整性校验用):-1 = 读循环没正常走完(中断/异常时不参与校验)
            long writtenTotal = -1;
            /** 本次是否为"剥壳落盘"(跳过容器头):剥壳后字节数与服务器声明不等,不能按声明长度校验 */
            int payloadOffsetOut = 0;
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
                // != -1 而不是 > 0:0 不代表 EOF(契约上 len>0 时不该返回 0,但 CipherInputStream 等
                // 实现会),按 >0 退出会把"还没读完"当"读完了",随后 .part 照样 rename 成 %05d.ts 当成功
                while ((n = is.read(buf)) != -1) {
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
                        DownloadExecutor.throttle(t, n);
                        continue;
                    }
                    if (DownloadExecutor.isInterrupted(t)) {
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
                    DownloadExecutor.throttle(t, n); // 5.4 增强: 每任务限速
                    // 存储看门狗自检(1s 缓存,每 64KB 一次调用也不会变成"每块 statfs"):
                    // 空间见底立即停止写盘并收尾(不抛异常,交给上方"中断"分支统一处理:
                    // 看门狗已把任务置为暂停,残留 .part 下次整段重下)
                    if (dm.watchdog.checkWhileDownloading()) {
                        os.flush();
                        t.segmentBytes = written;
                        dm.persist();
                        return;
                    }
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
                writtenTotal = written; // 读循环正常结束:记下实收字节供完整性校验
                payloadOffsetOut = payloadOffset;
            } finally {
                os.close(); // 无论成功/异常/中断都关闭 FileOutputStream,避免 StrictMode "resource failed to call close"
            }
            // 完整性校验:服务器声明了长度、本次又是"整段原样落盘"(未续传/未剥壳/未解密)时,
            // 实收字节必须与声明一致 —— 否则就是被提前掐断的残片,绝不能 rename 成 %05d.ts 当成功
            // (校验判据只有 exists() && length()>0,不比对长度的话残片会被当成完整分片合并进成品)
            if (key == null && payloadOffsetOut == 0 && segDone == 0 && declared > 0
                    && writtenTotal >= 0 && writtenTotal != declared) {
                FileCleaner.deleteQuietly(partFile);
                throw new IOException("分片不完整: 期望 " + declared + " B,实际 " + writtenTotal
                        + " B(连接被提前中断)");
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

    /**
     * 校验 206 响应声明的区间与清单一致。
     *
     * <p>为什么光看 Content-Length 不够:长度一致但<b>起点</b>错了(CDN 缓存串了区间/服务器算错偏移)时,
     * 存下来的字节数是对的、内容是别的片 —— 合并出的成品不会报错,只是花屏/串集,用户根本查不出原因。
     * Content-Range 缺失或格式不认识(如多区间的 {@code * /total})时返回 null(不误伤)。
     *
     * @return null=一致或无法判定;非 null=失败原因
     */
    private static String contentRangeMismatch(String header, HlsMediaPlaylist.ByteRange range) {
        if (header == null) return null;
        Matcher m = CONTENT_RANGE.matcher(header.trim());
        if (!m.matches()) return null;
        long start;
        long end;
        try {
            start = Long.parseLong(m.group(1));
            end = Long.parseLong(m.group(2));
        } catch (NumberFormatException e) {
            return null;
        }
        if (start == range.offset && end == range.endExclusive() - 1) return null;
        return "服务器返回的区间与清单不符(清单 " + range.describe() + ",服务器 " + start + "-" + end + ")";
    }

    /**
     * 分片 HTTP 状态码失败的统一构造(整文件下载与固定区间下载共用,避免两处文案/分类漂移):
     * 404/410 = 该片在源侧永久失效(死片,不再重试),其余状态码 = 可补救失败。
     */
    private static IOException segmentHttpFailure(int segIndex, String segUrl, int code) {
        return new DownloadErrors.HttpFailure(code, "分片");
    }
    /** 分片失败日志里 URL 的最大保留长度(见 {@link #segUrlForLog}) */
    static final int SEG_URL_LOG_MAX = 200;

    /**
     * 分片失败日志里带上"这一片到底是哪个地址"。
     * <p>
     * 为什么必须写进日志:先前分片失败只记片序号,用户报"下载一直 404"时分不清 404 是<b>源站</b>给的
     * (CDN 上就没有这个文件,换哪个下载器都一样)还是<b>本机回源代理</b>给的
     * ({@code 127.0.0.1:9978/proxy?do=...},那属于源 JS/代理的问题,能修)—— 两者的处置完全不同。
     * 那条 {@code Log.i("TVBox-Download", "分片 HTTP 404 url=…")} 进不了"错误日志"Tab:logcat 捕获
     * 默认只收 E 级(见 LogcatCapture.buildCommand),而这些行是 I 级,所以只有写进业务日志(Room)
     * 用户才看得到。
     * <p>
     * 长度夹到 {@value #SEG_URL_LOG_MAX} 字符:带签名/令牌的地址能长到几百字符,
     * 截断后 host 与前面一段路径仍在,足够认源。
     */
    static String segUrlForLog(String url) {
        if (url == null) return "";
        String u = url.trim();
        return u.length() <= SEG_URL_LOG_MAX ? u : u.substring(0, SEG_URL_LOG_MAX) + "…";
    }

    /**
     * 下载 fMP4 的 init 段({@code #EXT-X-MAP})到 {@code initFile}(分段目录里的 init.mp4)。
     *
     * <p>为什么不复用 {@link #downloadSegment}:那条路的内容判定是给"分片"写的 —— 它按 TS/fMP4 片段
     * (ftyp/styp/moof)判定,还会对"PNG 壳里藏 TS"的包裹型分片做剥壳;而 init 段是 <b>ftyp+moov</b>,
     * 既不是分片也不该被剥壳,口径完全不同。这里单独走一条不含解密的请求:
     * <ul>
     *   <li>带范围时用固定区间 Range,且<b>只接受 206</b>(200 = 服务器忽略 Range,存下来的会是整个资源);</li>
     *   <li>落盘前后做 fMP4 结构自检(必须含 moov 盒子)—— init 段拿错(错误页/加密后的密文)时,
     *       产物是"分片正常但整集解不出来",且要到播放阶段才发现;这里就地判失败,原因写清楚;</li>
     *   <li>中断(暂停/取消)时不留半成品:只写 .part,rename 之前先看中断标志。</li>
     * </ul>
     */
    private void downloadInitSegment(HlsMediaPlaylist.InitSegment init, File initFile, DownloadTask t)
            throws IOException {
        Map<String, String> headers = executor.baseHeaders(t);
        if (init.range != null) {
            headers.put("Range", init.range.headerValue());
        }
        Response resp = executor.getDownloadResponse(init.url, headers);
        dm.activeResponses.put(t.id, resp);
        File partFile = new File(initFile.getAbsolutePath() + ".part");
        try {
            int code = resp.code();
            if (init.range != null) {
                if (code == 200) {
                    // 确定性故障(每次重试都会拿到 200 整文件):带上"线路可疑"标记,让调度器直接换线路/重新解析,
                    // 而不是把重试次数白花在同一个结果上
                    throw new IOException("服务器忽略 Range 返回 200(整文件),无法按字节范围 "
                            + init.range.describe() + " 取 init 段(" + DownloadErrors.ROUTE_SUSPECT_TEXT + ")");
                }
                if (code != 206) {
                    throw new DownloadErrors.HttpFailure(code, "init 段");
                }
            } else if (code != 200) {
                throw new DownloadErrors.HttpFailure(code, "init 段");
            }
            long declared = resp.body().contentLength();
            File parent = initFile.getParentFile();
            if (parent != null && !parent.exists())
                parent.mkdirs();
            long written = 0;
            InputStream is = resp.body().byteStream();
            OutputStream os = new FileOutputStream(partFile, false);
            try {
                byte[] buf = new byte[DownloadManager.BUFFER];
                int n;
                while ((n = is.read(buf)) != -1) {
                    if (DownloadExecutor.isInterrupted(t)) {
                        os.flush();
                        FileCleaner.deleteQuietly(partFile);
                        dm.persist();
                        return; // 调用方会看到"未就绪"并自行收尾(与分片下载的中断语义一致)
                    }
                    os.write(buf, 0, n);
                    written += n;
                }
                os.flush();
            } finally {
                os.close();
            }
            if (declared > 0 && written != declared) {
                FileCleaner.deleteQuietly(partFile);
                throw new IOException("init 段不完整: 期望 " + declared + " B,实际 " + written + " B(连接被提前中断)");
            }
            if (init.range != null && written != init.range.length) {
                FileCleaner.deleteQuietly(partFile);
                throw new IOException("init 段区间长度不符: 清单 " + init.range.length + " B,实际 " + written + " B");
            }
            String problem = mp4InitProblem(partFile);
            if (problem != null) {
                FileCleaner.deleteQuietly(partFile);
                // 内容不对也是确定性的(同一地址再取还是这段字节):带"线路可疑"标记 → 换线路/重新解析,
                // 而不是把重试次数花在同一个坏 init 段上
                throw new IOException("init 段内容不是 fMP4 初始化数据(" + problem + "),该线路的 EXT-X-MAP 可能被替换/加密("
                        + DownloadErrors.ROUTE_SUSPECT_TEXT + ")");
            }
            if (!partFile.renameTo(initFile)) {
                FileCleaner.copyFile(partFile, initFile);
                FileCleaner.deleteQuietly(partFile);
            }
            Log.i("TVBox-Download", "init 段下载完成: " + initFile.length() + "B 来自 " + init.url
                    + (init.range == null ? "" : "[" + init.range.describe() + "]"));
            DownloadLog.LOG.success(DownloadSubType.PLAYLIST, "init 段下载完成 " + DownloadExecutor.formatSize(initFile.length())
                    + (init.range == null ? "" : "(" + init.range.describe() + ")"), DownloadLog.extras(t.episodeId));
        } finally {
            dm.activeResponses.remove(t.id);
            resp.close();
        }
    }

    /** fMP4 init 段结构自检所需的读取上限:init 段就是 ftyp+moov 的一小段,超出这个量级已不可能是它 */
    private static final int INIT_PROBE_BYTES = 256 * 1024;

    /**
     * init 段自检:必须是 MP4 盒子流且含 {@code moov}(解码所需的轨道信息就在里面)。
     * 不校验 moov 的话,拿回一段 HTML 错误页/图片也会被当成 init 段拼进产物 —— 那种成品"分片都对、
     * 整集却打不开",用户只能看到一个坏文件,还不知道坏在哪。
     *
     * @return null=通过;非 null=拒绝原因(不引用任何原文,避免把整段错误页写进日志)
     */
    private static String mp4InitProblem(File f) {
        byte[] head;
        try (InputStream is = new FileInputStream(f)) {
            int want = (int) Math.min(f.length(), INIT_PROBE_BYTES);
            head = new byte[want];
            int read = 0;
            while (read < want) {
                int n = is.read(head, read, want - read);
                if (n <= 0) break;
                read += n;
            }
            if (read < want) head = java.util.Arrays.copyOf(head, read);
        } catch (Throwable th) {
            return "读取失败: " + th;
        }
        if (head.length < 12) return "内容过短(" + head.length + "B)";
        String tag = new String(head, 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
        if (!isMp4BoxTag(tag)) {
            // 头一个盒子名不对:整段就不是 MP4 盒子流(常见:防盗链 HTML、图片、加密后的密文)
            TsProbe probe = TsProbe.of(head);
            return "首个盒子是 \"" + tag + "\""
                    + (probe.contentHint.isEmpty() ? "" : ",识别为" + probe.contentHint);
        }
        int moov = DownloadExecutor.indexOfAscii(head, "moov");
        if (moov < 0) return "不含 moov 盒子(无解码参数)";
        // moov 必须排在 moof/mdat 之前:MAP 指向的若是"媒体片段"(styp+moof+mdat),那段里也可能恰好
        // 出现 "moov" 字样,只有顺序判据能把它与真正的 init 段区分开
        int moof = DownloadExecutor.indexOfAscii(head, "moof");
        if (moof >= 0 && moof < moov) return "moov 之前先出现 moof 盒子(像是媒体片段,不是 init 段)";
        int mdat = DownloadExecutor.indexOfAscii(head, "mdat");
        if (mdat >= 0 && mdat < moov) return "moov 之前先出现 mdat 盒子(像是媒体片段,不是 init 段)";
        return null;
    }

    /** 合法/可容忍的 MP4 顶层盒子名(init 段一般以 ftyp 开头;部分封装直接以 moov/styp 开头) */
    private static boolean isMp4BoxTag(String tag) {
        return "ftyp".equals(tag) || "moov".equals(tag) || "styp".equals(tag)
                || "moof".equals(tag) || "free".equals(tag) || "skip".equals(tag) || "sidx".equals(tag);
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
        /** 与 {@link #segments} 一一对应的字节范围(null=整文件分片);切片型线路取片必须带范围 */
        final List<HlsMediaPlaylist.ByteRange> ranges;
        final List<HlsKey> keys;
        /** 该线路解析出来的防盗链请求头(每条线路可能各有各的 Referer) */
        final Map<String, String> headers;

        AltPlaylist(com.github.tvbox.osc.bean.DownloadRoute route, List<String> segments,
                List<HlsMediaPlaylist.ByteRange> ranges, List<HlsKey> keys, Map<String, String> headers) {
            this.route = route;
            this.segments = segments;
            this.ranges = ranges;
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
            List<Double> primaryDurations, boolean primaryFmp4) {
        List<Integer> remain = new ArrayList<>(missing);
        if (missing.isEmpty() || t.altRoutes == null || t.altRoutes.isEmpty()) return remain;
        if (primaryFmp4) {
            // fMP4 的替补片要能拼进去,前提是"另一条线路的 init 段与本线路完全一致"(解码参数/时间基相同),
            // 而这无法用 TS 的 PTS 接缝校验证明(那条校验只认 188 包 TS)。宁可不补、交给"缺片完成",
            // 也不冒着花屏/时间轴错乱的风险把别路的片段塞进我们的 init 段下。
            Log.i("TVBox-Download", "跨线路补片跳过:本线路是 fMP4(init 段无法跨线路证明一致): " + t.fileName);
            return remain;
        }
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
                + left.size() + " 片" + DownloadExecutor.missingList(left), DownloadLog.extras(t.episodeId));
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
            HlsPlaylistData data = parsePlaylist(pl[1], pl[0]);
            List<String> segs = data.urls;
            if (segs.isEmpty()) {
                Log.i("TVBox-Download", "跨线路补片:线路 " + route.describe() + " 播放列表无有效分片");
                return null;
            }
            if (data.init != null) {
                // 备用线路本身是 fMP4:它的片离开它自己的 init 段没法用(主流程也因此不跨线路补 fMP4),
                // 直接排除,不用等取片时才由 PTS 接缝校验拒掉(白下一个片)
                Log.i("TVBox-Download", "跨线路补片:线路 " + route.describe() + " 是 fMP4(init 段 "
                        + data.init + "),不参与补片");
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
            return new AltPlaylist(route, segs, data.ranges, data.keys, rr.headers);
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
        // 请求头用该线路自己解析出来的(每条线路可能各有各的 Referer);
        // 字节范围也必须带上 —— 切片型线路(整集一个文件)不带范围取回来的是整个文件
        HlsMediaPlaylist.ByteRange range = idx < alt.ranges.size() ? alt.ranges.get(idx) : null;
        downloadSegment(idx, alt.segments.get(idx), segFile, 0, t, key, keyCache, alt.headers, range);
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
        Map<String, String> headers = executor.baseHeaders(t);
        if (extraHeaders != null && !extraHeaders.isEmpty()) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) headers.put(e.getKey(), e.getValue());
            }
        }
        Response resp = executor.getDownloadResponse(url, headers);
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
                    Response resp2 = executor.getDownloadResponse(variant, headers);
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
    static String dirOfUrl(String url) {
        if (url == null) return "";
        int i = url.lastIndexOf('/');
        return i > 0 ? url.substring(0, i + 1) : "";
    }

    /** 数一份 m3u8 里的分片行数(非空且非注释),用于统计净化剔除了几片 */
    static int countSegmentLines(String playlist) {
        if (playlist == null || playlist.isEmpty()) return 0;
        int n = 0;
        for (String line : playlist.split("\r?\n")) {
            String l = line.trim();
            if (!l.isEmpty() && l.charAt(0) != '#') n++;
        }
        return n;
    }

    private String fetchPlaylist(String url, DownloadTask t) throws IOException {
        HlsManifestLoader.Manifest manifest = HlsManifestLoader.load(url, target -> {
            if (DownloadExecutor.isInterrupted(t)) throw new IOException("任务已停止");
            Response response = executor.getDownloadResponse(target, executor.baseHeaders(t));
            dm.activeResponses.put(t.id, response);
            try {
                if (!response.isSuccessful()) throw new DownloadErrors.HttpFailure(response.code(), "m3u8");
                return response.body().string();
            } finally { dm.activeResponses.remove(t.id, response); response.close(); }
        }, candidate -> {
            HlsMediaPlaylist.Result parsed = HlsMediaPlaylist.parse(candidate.url, candidate.text);
            if (!parsed.ok() || parsed.segments.isEmpty()) throw new IOException("变体清单无有效分片");
            if (candidate.url.equals(url) || t.totalSegments > 0) return;
            String purified = purifyForDownload(candidate.url, candidate.text);
            if (purified != null) parsed = HlsMediaPlaylist.parse(candidate.url, purified);
            if (!parsed.ok() || parsed.segments.isEmpty()) throw new IOException("变体清单无有效分片");
            // 第一个清晰度线路的分片本身不可访问时，也可在开跑前尝试其它变体。
            Map<String, String> headers = executor.baseHeaders(t);
            HlsMediaPlaylist.Segment first = parsed.segments.get(0);
            headers.put("Range", first.range == null ? "bytes=0-1023" : first.range.headerValue());
            Response probe = executor.getDownloadResponse(first.url, headers);
            dm.activeResponses.put(t.id, probe);
            try { if (!probe.isSuccessful()) throw new DownloadErrors.HttpFailure(probe.code(), "变体首片"); }
            finally { dm.activeResponses.remove(t.id, probe); probe.close(); }
        }, com.github.tvbox.osc.util.HlsMasterPlaylist.preferredHeight(t.episodeName), t.hlsMediaIdentity);
        synchronized (t) {
            if (!DownloadExecutor.isInterrupted(t)) {
                t.url = manifest.url;
                t.hlsMediaIdentity = PlaylistSnapshot.resourceIdentity(manifest.url);
            }
        }
        return manifest.text;
    }

    private static String purifyForDownload(String url, String playlist) {
        // 加密 IV、隐式字节范围和媒体序列不能在净化后按新下标推导。
        if (playlist.contains("#EXT-X-KEY") || playlist.contains("#EXT-X-BYTERANGE")
                || playlist.contains("#EXT-X-MAP") || playlist.contains("#EXT-X-MEDIA-SEQUENCE")) return null;
        return com.github.tvbox.osc.util.M3u8Purifier.removeMinorityUrl(dirOfUrl(url), playlist);
    }
    /**
     * 解析媒体播放列表的结果(下载侧形态):三张按序号并行对齐的表 + 可选 init 段。
     *
     * <p>为什么是并行表而不是"分片对象数组":下载循环/补片循环/校验/合并都按<b>序号</b>索引,
     * 并行表与既有 {@code segments}/{@code segKeys} 的用法完全一致,改动面最小。
     */
    private void verifyOrDownloadInit(HlsMediaPlaylist.InitSegment init, File file, DownloadTask task) throws IOException {
        if (!file.isFile() || file.length() == 0) { downloadInitSegment(init, file, task); return; }
        File check = new File(file.getParentFile(), ".init-check");
        try {
            downloadInitSegment(init, check, task);
            if (DownloadExecutor.isInterrupted(task)) throw new IOException("任务已停止");
            if (file.length() != check.length()) throw new DownloadErrors.LayoutChanged();
            try (InputStream old = new java.io.BufferedInputStream(new FileInputStream(file));
                 InputStream fresh = new java.io.BufferedInputStream(new FileInputStream(check))) {
                int a, b;
                do { a = old.read(); b = fresh.read(); if (a != b) throw new DownloadErrors.LayoutChanged(); } while (a != -1);
            }
        } finally { FileCleaner.deleteQuietly(check); FileCleaner.deleteQuietly(new File(check.getPath() + ".part")); }
    }

    private void refreshHlsSession(DownloadTask t, HlsPlaylistData current, Map<String, byte[]> keyCache)
            throws IOException {
        if (DownloadExecutor.isInterrupted(t)) throw new IOException("任务已停止");
        t.message = "鉴权及清单更新中（保留已下载分片）";
        dm.notifyChanged();
        if (!dm.scheduler.reResolveUrl(t)) throw new DownloadErrors.SessionExpired();
        String text;
        try { text = fetchPlaylist(t.url, t); }
        catch (IOException e) {
            if (DownloadErrors.isAuthentication(e) || DownloadErrors.reasonOf(e).contains("登录页"))
                throw new DownloadErrors.SessionExpired();
            throw e;
        }
        String purified = purifyForDownload(t.url, text);
        HlsPlaylistData newer = parsePlaylist(t.url, purified == null ? text : purified);
        if (!current.snapshot.canReuse(newer.snapshot)) throw new DownloadErrors.LayoutChanged();
        // 同一路径的 key 在续期后若实际字节变了，拒绝把不同内容的分片拼在一起。
        Map<String, byte[]> freshKeys = new HashMap<>();
        for (HlsKey key : newer.keys) {
            if (key == null || freshKeys.containsKey(key.keyUri)) continue;
            for (Map.Entry<String, byte[]> old : keyCache.entrySet()) {
                if (PlaylistSnapshot.keyIdentity(old.getKey()).equals(PlaylistSnapshot.keyIdentity(key.keyUri))) {
                    byte[] bytes = loadKeyBytes(key, t, freshKeys);
                    if (!java.util.Arrays.equals(old.getValue(), bytes)) throw new DownloadErrors.LayoutChanged();
                    break;
                }
            }
        }
        if (newer.init != null) verifyOrDownloadInit(newer.init,
                new File(executor.segmentsDirOf(t), INIT_SEGMENT_FILE), t);
        if (DownloadExecutor.isInterrupted(t)) throw new IOException("任务已停止");
        current.urls.clear(); current.urls.addAll(newer.urls);
        current.ranges.clear(); current.ranges.addAll(newer.ranges);
        current.keys.clear(); current.keys.addAll(newer.keys);
        current.init = newer.init;
        current.snapshot = newer.snapshot;
        keyCache.clear(); keyCache.putAll(freshKeys);
        t.message = "下载中（清单已续期）";
        dm.persist(); dm.notifyChanged();
        DownloadLog.LOG.info(DownloadSubType.RESOLVE, "清单续期成功，保留分片进度: " + t.fileName,
                DownloadLog.extras(t.episodeId));
    }
    private static final class HlsPlaylistData {
        final List<String> urls = new ArrayList<>();
        /** 与 {@link #urls} 一一对应:null=整文件就是这一片 */
        final List<HlsMediaPlaylist.ByteRange> ranges = new ArrayList<>();
        /** 与 {@link #urls} 一一对应:null=未加密 */
        final List<HlsKey> keys = new ArrayList<>();
        /** fMP4 的 init 段(null=不是 fMP4) */
        HlsMediaPlaylist.InitSegment init;
        PlaylistSnapshot snapshot;

        /** 是否存在"固定字节范围"分片(整集一个大文件切片的情形) */
        boolean hasRanges() {
            for (HlsMediaPlaylist.ByteRange r : ranges) {
                if (r != null) return true;
            }
            return false;
        }
    }

    /**
     * 解析媒体播放列表(委托 :common 的纯逻辑解析器 {@link HlsMediaPlaylist},它带 JVM 单测),
     * 并转成下载侧要用的"URL/字节范围/密钥"三张并行表 + init 段。
     *
     * <p>解析职责下沉到 :common 的原因:隐式 offset(接上一条同资源分片的末尾)、CRLF/BOM、
     * 异常清单的失败原因都能被纯单测钉死;这里只把解析结果翻成下载执行要用的形态。
     *
     * @throws IOException 清单不可用(原因来自解析器,如 {@code EXT-X-BYTERANGE 非法: xxx})
     */
    private HlsPlaylistData parsePlaylist(String playlistUrl, String playlist) throws IOException {
        HlsMediaPlaylist.Result r = HlsMediaPlaylist.parse(playlistUrl, playlist);
        if (!r.ok()) {
            // 以前这里对 fMP4/字节范围是"抱歉不支持"整集下不了;现在解析不出来必须给出具体原因,
            // 让用户/日志能分辨"清单本身坏了"还是"我们不支持的写法"
            throw new IOException("m3u8 解析失败: " + r.error);
        }
        for (String w : r.warnings) {
            Log.i("TVBox-Download", "播放列表警告: " + w);
        }
        HlsPlaylistData d = new HlsPlaylistData();
        d.init = r.init;
        d.snapshot = new PlaylistSnapshot(r, System.currentTimeMillis());
        for (int i = 0; i < r.segments.size(); i++) {
            HlsMediaPlaylist.Segment s = r.segments.get(i);
            d.urls.add(s.url);
            d.ranges.add(s.range);
            // 无显式 IV 时按 HLS 规范用该片的媒体序列号(16 字节大端)推导
            d.keys.add(toHlsKey(s.key, s.mediaSequence));
        }
        return d;
    }

    /**
     * 把解析器透传出来的 {@code #EXT-X-KEY} 属性翻成下载侧的解密参数。
     * 加密方式是否可解是<b>下载侧</b>的判断(解析器只管结构):AES-128 可解,
     * 其余(SAMPLE-AES 等)明确报错而不是下出一堆解密失败的碎片。
     */
    private HlsKey toHlsKey(HlsMediaPlaylist.Key k, long mediaSeq) throws IOException {
        if (k == null) return null;
        if (!"AES-128".equalsIgnoreCase(k.method)) {
            throw new IOException("暂不支持的 HLS 加密方式: " + k.method);
        }
        if (k.uri == null || k.uri.isEmpty()) {
            throw new IOException("EXT-X-KEY 缺少 URI");
        }
        byte[] iv = parseHexIv(k.iv);
        return new HlsKey(k.uri, iv != null ? iv : seqIv(mediaSeq));
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
        Response resp = executor.getDownloadResponse(key.keyUri, executor.baseHeaders(t));
        // 与其它请求一致登记,使暂停/删除/切网可中断在途的密钥请求(否则要等到读超时)。
        // 注意:调用点仍在读分段响应(它已占用 t.id 这个登记位),这里先顶替、finally 再还原,
        // 避免把分段响应一起摘掉导致后续暂停中断不到。
        Response replaced = dm.activeResponses.put(t.id, resp);
        try {
            if (!resp.isSuccessful())
                throw new DownloadErrors.HttpFailure(resp.code(), "HLS key");
            if (resp.body() == null) throw new IOException("HLS key 响应为空");
            byte[] data = readAes128Key(resp.body().byteStream());
            HlsKeyCheckpoint.verify(executor.segmentsDirOf(t), key.keyUri, data, t.doneSegments > 0);
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

    /** 只读到第 17 字节即可拒绝异常响应，避免密钥接口返回大文件时耗尽内存。 */
    static byte[] readAes128Key(InputStream input) throws IOException {
        byte[] limited = new byte[17];
        int count = 0;
        while (count < limited.length) {
            int n = input.read(limited, count, limited.length - count);
            if (n == -1) break;
            count += n;
        }
        if (count != 16) throw new IOException("HLS key 长度异常: " + (count == 17 ? ">16" : count) + "B");
        return java.util.Arrays.copyOf(limited, 16);
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
    static String resolveUrl(String original, String base, String seg) {
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

}
