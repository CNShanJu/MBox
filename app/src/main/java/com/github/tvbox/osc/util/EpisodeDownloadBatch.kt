package com.github.tvbox.osc.util

import android.util.Log
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.download.DownloadFacade
import com.github.tvbox.osc.download.DownloadRequest
import com.github.tvbox.osc.spiderapi.PlayUrlResolverProviders
import com.github.tvbox.osc.spiderapi.ResolveResult
import com.github.tvbox.osc.util.DownloadRoutePlan
import java.util.Locale

/**
 * 整剧批量入队下载协调逻辑(自 DetailActivity 抽出,缩小宿主类体积;Java→Kotlin 化,改进.txt §八):
 * 逐集解析真实地址(当前集优先复用播放器已解析结果,其余按源解析) → 拼集名/分辨率 → 统一剧集标识
 * → 经 [DownloadFacade] 入队并计数。宿主只负责线程调度、确认弹窗与结果提示。
 */
object EpisodeDownloadBatch {

    /** 当前播放剧集上下文(仅"当前集"复用播放器解析;不在播放该集时返回 null 均可) */
    interface CurrentEpisode {
        /** 播放器最终解析地址;无则返回 null */
        fun finalUrl(): String?

        /** 播放器当前请求头(防盗链);无则返回 null */
        fun playHeaders(): Map<String, String>?
    }

    /** 批量入队结果计数(与旧实现口径一致);@JvmField 保留公共字段供 Java 读写 */
    class Outcome {
        @JvmField
        var added: Int = 0

        @JvmField
        var downloadedExisted: Int = 0

        @JvmField
        var existedInQueue: Int = 0

        @JvmField
        var failed: Int = 0

        /** 因无存储权限被拒的集数(enqueue 硬门槛,任务未入队) */
        @JvmField
        var noPermission: Int = 0
    }

    /**
     * 批量入队完成后的提示文案(纯映射;UI 仅弹 toast)。优先「无存储权限」(任务未入队,必须引导授权)、
     * 其次「有新增」、再「均已存在(已下载/已在任务中)」、最后「全部失败」;空输入返回 null 由调用方处理。
     */
    @JvmStatic
    fun toastMessage(r: Outcome): String? {
        if (r.noPermission > 0) {
            return "未授权存储权限,无法下载。请先在系统设置授予「所有文件访问」权限"
        }
        if (r.added > 0) {
            val dups = r.downloadedExisted + r.existedInQueue
            return if (dups > 0) {
                "已加入 " + r.added + " 个下载任务," + dups + " 个已存在"
            } else {
                "已加入 " + r.added + " 个下载任务,可在\"我的-下载\"查看"
            }
        }
        if (r.downloadedExisted > 0 || r.existedInQueue > 0) {
            return when {
                r.downloadedExisted > 0 && r.existedInQueue > 0 ->
                    r.downloadedExisted.toString() + " 集已下载," + r.existedInQueue + " 集已在任务中"
                r.downloadedExisted > 0 -> "所选剧集均已下载完成"
                else -> "所选剧集已在下载任务中"
            }
        }
        if (r.failed > 0) {
            return if (r.failed > 1) {
                "所选剧集解析失败(" + r.failed + " 集),该源可能仅支持下载当前播放的剧集"
            } else {
                "该集解析失败,该源可能仅支持下载当前播放的剧集,请先播放该集再试"
            }
        }
        return null
    }

    /** 计算统一剧集标识:已有 episodeId 直接用;否则按集名在全集列表中的索引回退生成 */
    @JvmStatic
    fun resolveEpisodeId(
        sourceKey: String?,
        vodId: String?,
        playFlag: String?,
        seriesList: List<VodInfo.VodSeries>?,
        sName: String?,
        episodeId: String?
    ): String {
        if (episodeId != null && episodeId.isNotEmpty()) return episodeId
        return DownloadFacade.get().buildEpisodeId(sourceKey, vodId, playFlag, indexOfEpisode(seriesList, sName))
    }

    /** 集名在全集列表中的序号(找不到按 0,与旧口径一致) */
    @JvmStatic
    fun indexOfEpisode(seriesList: List<VodInfo.VodSeries>?, sName: String?): Int {
        if (seriesList == null || sName == null) return 0
        for (i in seriesList.indices) {
            val item = seriesList[i]
            if (item != null && item.name != null && item.name == sName) return i
        }
        return 0
    }

    /**
     * 入队结果归类:ok=true → added;
     * ok=false 按"已下载完成(状态1)"/"已在任务中"分别计入 downloadedExisted/existedInQueue。
     */
    @JvmStatic
    fun countEnqueueOutcome(ok: Boolean, episodeState: Int, out: Outcome?) {
        if (out == null) return
        if (ok) {
            out.added++
        } else {
            if (episodeState == 1) out.downloadedExisted++
            else out.existedInQueue++
        }
    }

    /**
     * 批量解析并入队。
     *
     * @param selected    已勾选的剧集列表
     * @param vodInfo     详情数据(sourceKey/id/pic/playFlag/seriesMap 均由此读取)
     * @param sourceName  来源名(仅用于入队/日志)
     * @param vodName     剧名(用于文件名)
     * @param currentName 当前正在播放的集名(可 null,用于识别"当前集"走播放器解析)
     * @param resLabel    分辨率标签(720P/2K/4K 等;由宿主按播放器画面尺寸生成,可 null)
     * @param current     当前播放上下文;可 null
     */
    @JvmStatic
    fun enqueue(
        selected: List<VodInfo.VodSeries>?,
        vodInfo: VodInfo?,
        sourceName: String?,
        vodName: String?,
        currentName: String?,
        resLabel: String?,
        current: CurrentEpisode?
    ): Outcome {
        val out = Outcome()
        val sel = selected
        val vi = vodInfo
        if (sel == null || sel.isEmpty() || vi == null) return out
        // 存储权限是入队硬门槛(DownloadScheduler 会直接拒绝):先检查,避免白白逐集解析地址后报误导性文案
        if (!DownloadFacade.get().hasStoragePermission()) {
            Log.i("TVBox-Download", "批量入队中止:无存储权限,已选 " + sel.size + " 集")
            out.noPermission = sel.size
            return out
        }
        val seriesList: List<VodInfo.VodSeries>? = vi.seriesMap?.get(vi.playFlag)
        if (seriesList == null || seriesList.isEmpty()) return out
        val sourceKey = vi.sourceKey
        val playFlag = vi.playFlag
        val vodId = vi.id
        // 播放地址解析经爬虫契约(:spider 实现由组合根注入;未注入时按"解析失败"处理)
        val resolver = PlayUrlResolverProviders.get()

        for (s in sel) {
            if (s == null) continue
            try {
                // 解析真实地址 + 源要求的请求头(防盗链源下载必须携带,否则"能播不能下")
                var rr: ResolveResult? = null
                if (s.name != null && s.name == currentName && current != null) {
                    val finalUrl = current.finalUrl()
                    if (!finalUrl.isNullOrEmpty()) {
                        // 当前集:解析失败回退播放地址,解析结果无头时补播放器 UA/Referer
                        rr = resolver.resolveCurrentWithPlaybackHeaders(
                            sourceKey, playFlag, s.url, current.playHeaders(), finalUrl
                        )
                    }
                }
                if (rr == null) {
                    rr = resolver.resolvePlayUrl(sourceKey, playFlag, s.url)
                }
                val url = rr?.url
                if (url.isNullOrEmpty() || !(url.startsWith("http://") || url.startsWith("https://"))) {
                    Log.i("TVBox-Download", "  - " + s.name + " 解析失败/无有效地址,跳过")
                    out.failed++
                    continue
                }
                // 文件名拼接分辨率:剧名_集名_720P.mp4;集名已含分辨率字样则不多拼;单集(名=剧名)不拼
                var epName = s.name
                if (resLabel != null && !s.name.isNullOrEmpty()
                    && s.name != vodName && !containsResolution(s.name)
                ) {
                    epName = s.name + "_" + resLabel
                }
                // 统一剧集标识(与详情页选集一一对应,精确去重)
                val epIndex = indexOfEpisode(seriesList, s.name)
                val episodeId = resolveEpisodeId(sourceKey, vodId, playFlag, seriesList, s.name, s.episodeId)
                // 备用线路(换线路重下用,4.8②):同一集在其它线路下的原始地址,按集名/序号对齐;
                // 入队时就地取材(详情页 seriesMap 已有全部线路),换线路时不必再查一次剧集详情
                val altRoutes = DownloadRoutePlan.alternatives(
                    vi.seriesMap, playFlag, s.url, epIndex, s.name
                )
                val ok = DownloadFacade.get().enqueue(
                    DownloadRequest(
                        url, sourceKey, playFlag, s.url, episodeId,
                        vi.pic, rr?.headers, sourceName, vodName, epName, altRoutes
                    )
                )
                Log.i("TVBox-Download", "  - " + s.name + " enqueue=" + ok + " 文件名=" + epName
                    + " 备用线路=" + altRoutes.size + " url=" + url)
                // 归类:added / 已下载完成(状态1) / 已在任务中
                countEnqueueOutcome(ok, DownloadFacade.get().getEpisodeState(episodeId, sourceName, vodName, s.name), out)
            } catch (th: Throwable) {
                Log.e("TVBox-Download", "批量入队异常: " + (s.name ?: ""), th)
                out.failed++
            }
        }
        return out
    }

    /** 集名是否已含分辨率字样(4K/2K/1080P/720P 等),含则不再拼接 */
    @JvmStatic
    fun containsResolution(name: String?): Boolean {
        if (name == null) return false
        val n = name.uppercase(Locale.ROOT)
        return n.contains("4K") || n.contains("2K") || n.contains("2160P") || n.contains("1440P")
            || n.contains("1080P") || n.contains("720P") || n.contains("480P") || n.contains("360P")
    }

    /** 由视频宽高生成分辨率标签(高≥2000→4K;≥1400→2K;≥1000→1080P;≥700→720P;≥500→480P);未知返回 null */
    @JvmStatic
    fun resolutionLabel(size: IntArray?): String? {
        if (size == null || size.size < 2) return null
        val h = size[1]
        if (h <= 0) return null
        return when {
            h >= 2000 -> "4K"
            h >= 1400 -> "2K"
            h >= 1000 -> "1080P"
            h >= 700 -> "720P"
            h >= 500 -> "480P"
            else -> null
        }
    }
}
