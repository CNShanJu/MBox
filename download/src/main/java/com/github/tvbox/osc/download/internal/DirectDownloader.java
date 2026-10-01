package com.github.tvbox.osc.download.internal;

import android.util.Log;

import com.github.tvbox.osc.bean.DownloadTask;
import com.github.tvbox.osc.download.DownloadSubType;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

import okhttp3.Response;

class DirectDownloader {
    private final DownloadManager dm;
    private final DownloadExecutor executor;
    DirectDownloader(DownloadManager dm, DownloadExecutor executor) { this.dm = dm; this.executor = executor; }

    void download(DownloadTask t) throws IOException {
        Map<String, String> headers = executor.baseHeaders(t);
        // 续传前先按磁盘实况校正计数(与 HLS 侧 countExistingSegments 同一原则)
        reconcilePartWithDisk(t);
        if (t.downloadedBytes > 0) {
            headers.put("Range", "bytes=" + t.downloadedBytes + "-");
            String validator = com.github.tvbox.osc.util.DirectResumePolicy.validator(t.entityTag, t.lastModified);
            if (validator != null) headers.put("If-Range", validator);
        }
        Response resp = executor.getDownloadResponse(t.url, headers);
        dm.activeResponses.put(t.id, resp);
        try {
            // 内容级 HLS 识别:代理/伪装 URL 不含 .m3u8,但实际返回的是 m3u8 播放列表
            if (executor.isM3u8Response(resp)) {
                Log.i("TVBox-Download", "内容识别为 m3u8,转 HLS 下载: " + t.fileName);
                resp.close();
                dm.activeResponses.remove(t.id);
                if (t.downloadedBytes > 0) {
                    t.downloadedBytes = 0;
                    FileCleaner.deleteQuietly(new File(t.partPath));
                }
                executor.downloadHls(t);
                return;
            }
            int code = resp.code();
            if (code == 206 && !com.github.tvbox.osc.util.DirectResumePolicy.matches(resp.header("Content-Range"), t.downloadedBytes)) {
                throw new IOException("服务器续传偏移不一致，已保留文件，请重试或换源");
            }
            if (code == 200 && t.downloadedBytes > 0) {
                // 服务器不支持断点,从头开始
                t.downloadedBytes = 0;
                t.totalBytes = 0;
                FileCleaner.deleteQuietly(new File(t.partPath));
            } else if (code != 200 && code != 206) {
                throw new DownloadErrors.HttpFailure(code, "视频");
            }
            t.entityTag = resp.header("ETag");
            t.lastModified = resp.header("Last-Modified");
            // 内容校验:返回的是 HTML 网页/防盗链页而非视频,直接判失败,不保存垃圾文件
            if (executor.isHtmlResponse(resp)) {
                throw new IOException("响应不是视频内容(可能为网页或防盗链页)");
            }
            {
                String cl = resp.header("Content-Length");
                if (cl != null) {
                    t.totalBytes = t.downloadedBytes + Long.parseLong(cl);
                }
            }
            // 响应头识别真实扩展名(代理/无后缀 URL 会隐藏格式):首次下载时在写 .part 前修正文件名
            if (t.downloadedBytes == 0) {
                String realExt = executor.detectExtensionFromResponse(resp);
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
                    if (!executor.isPlausibleVideo(head, t.fileName)) {
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
                while ((n = is.read(buf)) != -1) { // != -1:0 不是 EOF(见分片循环处的说明)
                    if (DownloadExecutor.isInterrupted(t)) {
                        os.flush();
                        t.speed = 0;
                        dm.persist();
                        dm.notifyChanged();
                        return;
                    }
                    os.write(buf, 0, n);
                    t.downloadedBytes += n;
                    DownloadExecutor.throttle(t, n); // 5.4 增强: 每任务限速
                    long now = System.currentTimeMillis();
                    if (now - lastPersist > 500) {
                        // 存储看门狗自检(1s 缓存,这里 500ms 一次不会变成"每块 statfs"):
                        // 空间见底 → 看门狗已把本任务置为暂停并关闭连接,这里立即收尾退出
                        // (否则会继续把 buf 里的字节写进已经没有空间的卷)
                        if (dm.watchdog.checkWhileDownloading()) {
                            os.flush();
                            t.speed = 0;
                            dm.persist();
                            dm.notifyChanged();
                            return;
                        }
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
            if (DownloadExecutor.isInterrupted(t)) {
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

}
