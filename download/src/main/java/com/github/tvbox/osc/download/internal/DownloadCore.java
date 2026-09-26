package com.github.tvbox.osc.download.internal;

import com.github.tvbox.osc.bean.DownloadTask;
import com.github.tvbox.osc.bean.VodInfo;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 下载领域服务门面:上层(详情页/播放页/下载页)只关心"这一集的下载状态",不触碰
 * savePath/tmpDir/分片等底层细节;统一通过本类创建任务、查询状态、控制任务。
 * <p>
 * 统一剧集标识 EpisodeId = sourceKey|vodId|playFlag|playIndex 的稳定四元组,
 * 替代"按名字/文件名模糊匹配",同一集在任意入口状态一致。
 */
public class DownloadCore {

    private DownloadCore() {
    }

    // ------------------------------------------------------------------
    // EpisodeId 构建
    // ------------------------------------------------------------------

    /**
     * 由播放上下文构建统一剧集标识。
     *
     * @param sourceKey 来源 key
     * @param vodId     影片 id(VodInfo.id)
     * @param playFlag  线路名
     * @param playIndex 集索引
     */
    public static String buildEpisodeId(String sourceKey, String vodId, String playFlag, int playIndex) {
        return (sourceKey == null ? "" : sourceKey) + "|"
                + (vodId == null ? "" : vodId) + "|"
                + (playFlag == null ? "" : playFlag) + "|"
                + playIndex;
    }

    /** 由 VodInfo 与当前选集构建 EpisodeId */
    public static String buildEpisodeId(VodInfo vodInfo, int playIndex) {
        if (vodInfo == null) return null;
        return buildEpisodeId(vodInfo.sourceKey, vodInfo.id, vodInfo.playFlag, playIndex);
    }

    // ------------------------------------------------------------------
    // 任务查询
    // ------------------------------------------------------------------

    /** 按 EpisodeId 查任务(任意状态);无则返回 null */
    public static DownloadTask getTaskByEpisode(String episodeId) {
        if (episodeId == null || episodeId.isEmpty()) return null;
        for (DownloadTask t : DownloadManager.get().getTasks()) {
            if (episodeId.equals(t.episodeId)) return t;
        }
        return null;
    }

    /**
     * 批量按 EpisodeId 查询下载状态(一次快照,避免逐集重复拷贝任务列表):
     * 返回数组与 episodeIds 一一对应,0=未下载;1=已下载完成且文件存在;2=已有任务。
     */
    public static int[] getEpisodeStates(String[] episodeIds, String sourceName, String vodName, String[] episodeNames) {
        int[] states = new int[episodeIds == null ? 0 : episodeIds.length];
        if (episodeIds == null || episodeIds.length == 0) return states;
        // 一次快照 + 构建 episodeId -> task 索引
        Map<String, DownloadTask> byEpisode = new java.util.HashMap<>();
        List<DownloadTask> all = DownloadManager.get().getTasks();
        for (DownloadTask t : all) {
            if (t.episodeId != null && !t.episodeId.isEmpty()) {
                byEpisode.putIfAbsent(t.episodeId, t);
            }
        }
        for (int i = 0; i < episodeIds.length; i++) {
            String eid = episodeIds[i];
            if (eid != null && !eid.isEmpty() && !eid.contains("||")) {
                // 5.3: 已下载档案(长期)优先——任务记录清理后详情页"已下载"仍准确
                if (com.github.tvbox.osc.download.internal.DownloadArchive.get().isDownloaded(eid)) {
                    states[i] = 1;
                    continue;
                }
                DownloadTask t = byEpisode.get(eid);
                if (t != null) {
                    if (t.state == DownloadTask.STATE_COMPLETED) {
                        states[i] = t.savePath != null && new File(t.savePath).exists() ? 1 : 0;
                    } else if (t.state == DownloadTask.STATE_FAILED) {
                        states[i] = 3; // 失败: 单独一档(抽屉显示失败,可重新下载)
                    } else {
                        states[i] = 2; // 下载中/排队/暂停等
                    }
                    continue;
                }
            }
            // 旧任务(无 episodeId)回退:按来源+剧名+集名匹配
            states[i] = DownloadManager.get().getEpisodeDownloadState(
                    sourceName, vodName, episodeNames != null && i < episodeNames.length ? episodeNames[i] : null);
        }
        return states;
    }

    /**
     * 某集的下载状态:0=未下载,可下载;1=已下载完成且文件存在;2=已有任务(下载中/排队/暂停/失败等)。
     * 优先用 EpisodeId 精确匹配;无 episodeId 的旧任务回退到 来源+剧名+剧集名 匹配。
     */
    public static int getEpisodeState(String sourceKey, String vodId, String playFlag, int playIndex,
                                      String sourceName, String vodName, String episodeName) {
        return getEpisodeState(buildEpisodeId(sourceKey, vodId, playFlag, playIndex), sourceName, vodName, episodeName);
    }

    /** 同 {@link #getEpisodeState(String, String, String, int, String, String, String)},直接传入已构建的 EpisodeId */
    public static int getEpisodeState(String episodeId, String sourceName, String vodName, String episodeName) {
        if (episodeId != null && !episodeId.isEmpty() && !episodeId.contains("||")) {
            // 5.3: 已下载档案(长期)优先
            if (com.github.tvbox.osc.download.internal.DownloadArchive.get().isDownloaded(episodeId)) {
                return 1;
            }
            DownloadTask t = getTaskByEpisode(episodeId);
            if (t != null) {
                if (t.state == DownloadTask.STATE_COMPLETED) {
                    return t.savePath != null && new File(t.savePath).exists() ? 1 : 0;
                }
                if (t.state == DownloadTask.STATE_FAILED) {
                    return 3; // 失败: 可重新下载
                }
                return 2;
            }
        }
        // 旧任务(无 episodeId)回退:按来源+剧名+集名匹配
        return DownloadManager.get().getEpisodeDownloadState(sourceName, vodName, episodeName);
    }

    /** 是否已下载完成且文件存在（档案优先，任务回退） */
    public static boolean isDownloaded(String episodeId) {
        if (episodeId != null && !episodeId.isEmpty()) {
            if (com.github.tvbox.osc.download.internal.DownloadArchive.get().isDownloaded(episodeId)) {
                return true;
            }
        }
        DownloadTask t = getTaskByEpisode(episodeId);
        return t != null && t.state == DownloadTask.STATE_COMPLETED
                && t.savePath != null && new File(t.savePath).exists();
    }

    // ------------------------------------------------------------------
    // 任务控制(薄门面,转发 DownloadManager)
    // ------------------------------------------------------------------

    /** 暂停单任务 */
    public static void pause(DownloadTask t) {
        DownloadManager.get().pause(t);
    }

    /** 继续单任务 */
    public static void resume(DownloadTask t) {
        DownloadManager.get().resume(t);
    }

    /** 全部暂停 */
    public static void pauseAll() {
        DownloadManager.get().pauseAll();
    }

    /** 全部开始 */
    public static void startAll() {
        DownloadManager.get().startAll();
    }

    /**
     * 删除任务:确认删除后统一走此入口。
     *
     * @param deleteFiles true=连本地文件一起删;false=只删记录保留文件
     */
    public static void remove(DownloadTask t, boolean deleteFiles) {
        DownloadManager.get().remove(t, deleteFiles);
    }

    /** 任务快照(安全遍历) */
    public static List<DownloadTask> getTasks() {
        return DownloadManager.get().getTasks();
    }
}
