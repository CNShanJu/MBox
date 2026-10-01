package com.github.tvbox.osc.download.internal;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import com.github.tvbox.osc.bean.DownloadTask;
import com.github.tvbox.osc.download.DownloadSubType;
import com.github.tvbox.osc.state.StorageSpace;
import com.github.tvbox.osc.util.TsProbe;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;


class MediaRemuxer {
    private final DownloadManager dm;
    private final DownloadExecutor executor;
    MediaRemuxer(DownloadManager dm, DownloadExecutor executor) { this.dm = dm; this.executor = executor; }

    boolean remux(DownloadTask t, File src) {
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
            // 空间预检必须在"重打包"之前:重打包本身就要再写一份全量文件
            if (!ensureRemuxSpace(t, src, probe))
                return false;
            File remuxSrc = src;
            if (probe.needsRepack()) {
                repacked = new File(src.getParentFile(), "repack_" + System.currentTimeMillis() + ".ts");
                if (repackTo188(src, repacked, probe)) {
                    remuxSrc = repacked;
                    Log.i("TVBox-Download", "重封装:已重打包为 188 字节包 " + DownloadExecutor.formatSize(repacked.length()));
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
            // 源时长(校验 stop() 失败后的产物是否完整用;拿不到就 0 = 不做时长比对)
            long srcDurationUs = maxDurationUs(videoFormat, audioFormat);

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
            //
            // 必须是 **直接缓冲(allocateDirect)**:readSampleData 与 writeSampleData 都是 native 实现,
            // 经 GetDirectBufferAddress 取数据地址,堆缓冲(allocate)拿不到地址 → 抛
            // IllegalArgumentException,被下面 catch(Throwable) 吞掉 → 一路回退 .ts ——
            // 表现就是"HLS 下载成品永远变不成 MP4"(3.5.7 引入重封装后一直如此)。
            // 堆缓冲还多一次 JVM↔native 拷贝,直接缓冲在正确性与性能上都更优。
            ByteBuffer buffer = ByteBuffer.allocateDirect(8 * 1024 * 1024);
            // 多段 TS 字节拼接后段间 PTS 可能回跳/重置,而 MediaMuxer 要求每轨单调不减,
            // 回退即抛 IllegalArgumentException 导致整个重封装失败——按轨钳制到 lastPts+1
            long lastVideoPts = Long.MIN_VALUE;
            long lastAudioPts = Long.MIN_VALUE;
            while (true) {
                int track = extractor.getSampleTrackIndex();
                if (track < 0)
                    break;
                buffer.clear(); // 每样本前复位(direct 缓冲也复用同一块,别把上一次的数据/位置带过来)
                int size = extractor.readSampleData(buffer, 0);
                if (size < 0) {
                    // 缓冲区不足:扩容后重读同一样本(advance 前可重复调用);已封顶则跳过该样本
                    if (buffer.capacity() >= 64 * 1024 * 1024) {
                        extractor.advance();
                        continue;
                    }
                    // 扩容同样必须 direct:换成堆缓冲会让本来正常的大样本直接抛异常
                    buffer = ByteBuffer.allocateDirect(buffer.capacity() * 2);
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
            // stop() 容错(见方法头 ②):MPEG4Writer 可能已经把 moov 写完、每一帧也都编码了,
            // stop() 仍抛 "Error during stop(), muxer would have stopped already"(-1007)。
            // 这时侯先把 muxer 收干净,再校验产物 —— 可用就照旧采用,真坏了才回退 .ts。
            boolean stopFailed = false;
            Throwable stopErr = null;
            try {
                muxer.stop();
            } catch (Throwable th) {
                stopFailed = true;
                stopErr = th;
            }
            try {
                muxer.release();
            } catch (Throwable ignored) {
            }
            muxer = null;
            extractor.release();
            extractor = null;
            if (stopFailed) {
                Log.w("TVBox-Download", "重封装:MediaMuxer.stop() 报错,校验产物是否可用 -> " + stopErr, stopErr);
                if (!mp4Usable(outTmp, srcDurationUs)) {
                    // 产物确实不可用(被截断/读不出来):保持原行为,回退 .ts
                    return false;
                }
                Log.w("TVBox-Download", "重封装:stop() 报错但产物校验通过(时长/末尾样本都在),仍采用 MP4 成品: "
                        + src.getName());
                DownloadLog.LOG.warn(DownloadSubType.REMUX,
                        "重封装:系统 stop() 报错(" + (stopErr == null ? "?" : stopErr.getMessage())
                                + "),但成品校验通过,已按 MP4 采用: " + t.fileName,
                        DownloadLog.extras(t.episodeId));
            }
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

    /** stop() 报错后的产物校验:允许的时长缺口(源时长的百分比,低于它按"产物不完整"处理) */
    private static final int REMUX_MIN_DURATION_PERCENT = 90;

    /** 两组轨道格式里能拿到的最大时长(µs);都没有返回 0 */
    private static long maxDurationUs(MediaFormat a, MediaFormat b) {
        long d = 0;
        if (a != null && a.containsKey(MediaFormat.KEY_DURATION)) {
            try {
                d = a.getLong(MediaFormat.KEY_DURATION);
            } catch (Throwable ignored) {
            }
        }
        if (b != null && b.containsKey(MediaFormat.KEY_DURATION)) {
            try {
                d = Math.max(d, b.getLong(MediaFormat.KEY_DURATION));
            } catch (Throwable ignored) {
            }
        }
        return d;
    }

    /**
     * 重封装前的空间预检 + 释放碎片目录(见 {@link #remuxTsToMp4} 方法头 ①)。
     *
     * <p>需求 = 源文件大小 × (不重打包 2 / 重打包 3):不重打包时"源 .ts + 新 mp4"两份同时在;
     * 192/204 重打包时还要多一份 repack 中间件。保底只要求绝对下限
     * {@link DownloadPolicy#MIN_ABSOLUTE_FREE}(512MB),<b>不</b>按看门狗阈值(1GB)拦 ——
     * 这是已经下完的文件的最后一步,为它回退 .ts(用户最不想要的结果)不划算,
     * 真正把设备写满的风险由"动手前算得准 + 512MB 保底"兜住。
     *
     * <p>空间不够时先释放本任务的分片目录(合并产物已经在,碎片只是"失败可低成本重试"的保险,
     * 这里拿它换空间);仍不够才失败并写清原因。**释放碎片是有代价的**:若此后档案写入失败导致整任务
     * 重试,碎片已不在、需要重下 —— 这是"空间不够"下唯一可选的取舍,故落日志留痕。
     *
     * @return true = 空间够,可以继续重封装;false = 空间不足(调用方回退 .ts)
     */
    private boolean ensureRemuxSpace(DownloadTask t, File src, TsProbe probe) {
        File dir = src == null ? null : src.getParentFile();
        // 2 = 源 + 新 mp4;3 = 再加一份重打包中间件(重打包失败时按 2 也算够,这里偏保守无害)
        long copies = probe != null && probe.needsRepack() ? 3 : 2;
        long need = src == null ? 0 : src.length() * copies;
        if (dir == null || need <= 0)
            return true;
        if (hasRoomFor(dir, need))
            return true;
        long freeBefore = freeBytesOf(dir);
        if (t != null) {
            executor.deleteSegmentsDir(t);
            Log.i("TVBox-Download", "重封装:空间不足,先释放碎片目录腾空间(" + DownloadExecutor.formatSize(freeBefore) + " → "
                    + DownloadExecutor.formatSize(freeBytesOf(dir)) + "): " + t.fileName);
            DownloadLog.LOG.warn(DownloadSubType.REMUX,
                    "重封装:空间不足,已释放分片目录腾空间(该任务此后若失败重试需重下): " + t.fileName,
                    DownloadLog.extras(t.episodeId));
        }
        if (hasRoomFor(dir, need))
            return true;
        long free = freeBytesOf(dir);
        String why = "重封装:可用空间不足(写 MP4 需约 " + DownloadExecutor.formatSize(need) + ",释放碎片后可用 " + DownloadExecutor.formatSize(free)
                + "),保留 .ts 成品";
        Log.w("TVBox-Download", why + ": " + src.getAbsolutePath());
        DownloadLog.LOG.warn(DownloadSubType.REMUX,
                why + ": " + (t == null ? src.getName() : t.fileName),
                DownloadLog.extras(t == null ? null : t.episodeId));
        return false;
    }

    /** 该目录所在卷是否放得下 need 字节并留出绝对保底空间;测不出来(未知)一律放行 */
    private static boolean hasRoomFor(File dir, long need) {
        try {
            long free = StorageSpace.freeBytes(dir);
            if (free < 0) return true; // 测量失败按"未知"放行(与其余空间判定同一口径)
            return free - need >= DownloadPolicy.MIN_ABSOLUTE_FREE;
        } catch (Throwable th) {
            return true;
        }
    }

    /** 目录所在卷可用字节(未知返回 0,只用于日志文案) */
    private static long freeBytesOf(File dir) {
        try {
            long free = StorageSpace.freeBytes(dir);
            return free < 0 ? 0 : free;
        } catch (Throwable th) {
            return 0;
        }
    }

    /**
     * 校验 mux 出来的 MP4 是否<b>确实可用</b>(只在 {@code MediaMuxer.stop()} 报错后才走这里)。
     * <p>
     * 为什么要校验而不是直接采信失败:实测(魅族/MTK + 长剧集)日志里 {@code MPEG4Writer} 已经打出
     * {@code Received total ... encoded N frames}(每帧都编码了)与 {@code MOOV atom was written to the file},
     * 紧接着 {@code stop()} 才报 -1007 —— 文件其实是完整的,旧实现却因为一个异常把整个 MP4 丢掉、回退 .ts。
     * 但也不能无条件采信异常后的产物(真被截断时用户会拿到"能打开、看着看着断掉"的文件),故三条硬校验:
     * <ol>
     *   <li>能开出提取器、有轨、报得出时长(&gt;0);</li>
     *   <li>时长不短于源时长的 {@value #REMUX_MIN_DURATION_PERCENT}%(源时长拿不到时跳过这条);</li>
     *   <li><b>末尾样本读得出来</b>:moov 里有时长、实际数据却被截断时,seek 到末尾再读会失败。</li>
     * </ol>
     * 任一条不满足 → 返回 false,调用方照旧回退 .ts(不静默交出可疑产物)。
     */
    private static boolean mp4Usable(File f, long expectDurationUs) {
        if (f == null || !f.exists() || f.length() <= 0)
            return false;
        MediaExtractor ex = null;
        try {
            ex = openExtractor(f); // FD 优先:私有下载目录下媒体服务按路径读不到(见 openExtractor)
            long dur = 0;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat fmt = ex.getTrackFormat(i);
                if (fmt.containsKey(MediaFormat.KEY_DURATION)) {
                    dur = Math.max(dur, fmt.getLong(MediaFormat.KEY_DURATION));
                }
            }
            if (dur <= 0)
                return false;
            if (expectDurationUs > 0 && dur < expectDurationUs * REMUX_MIN_DURATION_PERCENT / 100)
                return false;
            ex.seekTo(Math.max(0, dur - 1), MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            if (ex.getSampleTrackIndex() < 0)
                return false;
            // 直接缓冲:readSampleData 是 native 实现(堆缓冲取不到地址会抛异常,见 RemuxBufferContractTest)
            ByteBuffer probe = ByteBuffer.allocateDirect(4096);
            return ex.readSampleData(probe, 0) > 0;
        } catch (Throwable th) {
            Log.w("TVBox-Download", "重封装:stop() 报错后的产物校验失败 -> " + th);
            return false;
        } finally {
            if (ex != null) {
                try {
                    ex.release();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 读文件头做结构自检(4KB 足够验多个包的同步字节) */
    static TsProbe probeHead(File f) {
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
            while (fill < buf.length && (n = in.read(buf, fill, buf.length - fill)) != -1) {
                if (n == 0) continue; // 0 不是 EOF:继续攒(否则会把没读完的块当末尾残块)
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

}