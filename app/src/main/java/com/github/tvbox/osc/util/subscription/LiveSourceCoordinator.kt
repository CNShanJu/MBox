package com.github.tvbox.osc.util.subscription

import com.github.tvbox.osc.config.SystemConfig
import com.github.tvbox.osc.log.Category
import com.github.tvbox.osc.log.LogStore
import com.github.tvbox.osc.spiderapi.LiveChannelConfigApi
import com.github.tvbox.osc.spiderapi.LiveChannelConfigProviders
import com.github.tvbox.osc.ui.state.SubscriptionPageState
import com.github.tvbox.osc.util.LiveConfig
import com.github.tvbox.osc.util.LiveSourceEntries
import com.github.tvbox.osc.util.LiveSourceExporter

/**
 * 「直播源」页协调器:列表重建、未生效订阅的直播预览(epoch 防旧结果覆盖)、
 * 当前直播源切换与删除。只算数据和做配置落库,视图渲染经 [SubscriptionLiveHost] 回投。
 */
class LiveSourceCoordinator(
    private val state: SubscriptionPageState,
    private val host: SubscriptionLiveHost
) {
    /** 未生效订阅的直播清单只用于本页预览；回调用 epoch 防止切源后旧结果覆盖新列表。 */
    private val previewCache = HashMap<String, List<LiveChannelConfigApi.SubscribeLiveSource>>()
    private var previewLoadingUrl: String? = null
    private var previewFailedUrl: String? = null
    private var previewSelectedUrl = ""
    private var previewEpoch = 0

    /**
     * 重建直播源列表:订阅导入的在前(不可删)、用户自建的在后;勾中的那条 = 当前生效的直播源。
     * 订阅导入的**不落库**。已加载的订阅读内存；页面里刚选中但还未生效的订阅读只读预览，
     * 不加载爬虫，也不把上一条订阅的直播源误标成新订阅的内容。
     */
    fun refreshEntries(): LiveSourceSnapshot {
        val provider = LiveChannelConfigProviders.get()
        val selected = state.selectedUrl
        if (previewSelectedUrl != selected) {
            previewSelectedUrl = selected
            previewEpoch++
            previewLoadingUrl = null
            previewFailedUrl = null
        }
        val importedSources = when {
            selected.isEmpty() -> emptyList()
            provider.loadedSubscriptionUrl == selected -> provider.subscribeLiveSources
            previewCache.containsKey(selected) -> previewCache[selected].orEmpty()
            else -> {
                requestLivePreview(selected)
                emptyList()
            }
        }
        val imported = importedSources.map { LiveSourceEntries.Imported(it.name, it.url) }
        val userHistory = LiveConfig.liveHistory()
        val activeLiveUrl = SystemConfig.getLiveUrl()
        val oldImported = selected != provider.loadedSubscriptionUrl &&
            provider.subscribeLiveSources.any { it.url == activeLiveUrl } &&
            !userHistory.contains(activeLiveUrl)
        val displayedLiveUrl = if (oldImported && imported.none { it.url == activeLiveUrl }) ""
        else activeLiveUrl
        val entries = LiveSourceEntries.build(
            state.currentName(),
            imported,
            userHistory,
            LiveConfig.liveNames(),
            displayedLiveUrl
        )
        val preview = previewStateOf(provider.loadedSubscriptionUrl)
        val empty = entries.isEmpty()
        val emptyText = if (empty) {
            if (previewLoadingUrl == selected) "正在读取所选订阅的直播源…"
            else "暂无直播源\n点右上角 + 添加自己的直播源\n订阅自带的直播源会自动出现在这里"
        } else ""
        return LiveSourceSnapshot(entries, preview.visible, preview.text, preview.clickable, empty, emptyText)
    }

    private fun requestLivePreview(url: String) {
        if (previewLoadingUrl == url || previewFailedUrl == url) return
        previewLoadingUrl = url
        val epoch = ++previewEpoch
        val owner = java.lang.ref.WeakReference(host)
        LiveChannelConfigProviders.get().previewSubscribeLiveSources(url) { sources, error ->
            val page = owner.get() ?: return@previewSubscribeLiveSources
            page.runOnUi {
                if (!page.isPageActive() || epoch != previewEpoch || state.selectedUrl != url)
                    return@runOnUi
                previewLoadingUrl = null
                if (error == null) previewCache[url] = sources.toList()
                else previewFailedUrl = url
                page.onLiveSourcesRenderNeeded()
            }
        }
    }

    private class PreviewState(val visible: Boolean, val clickable: Boolean, val text: String)

    private fun previewStateOf(loadedUrl: String): PreviewState {
        val selected = state.selectedUrl
        if (selected.isEmpty() || selected == loadedUrl) {
            return PreviewState(false, false, "")
        }
        val name = state.currentName().ifBlank { "所选视频源" }
        val suffix = if (selected != state.beforeUrl) " · 退出后生效" else ""
        val text = when {
            previewLoadingUrl == selected -> "正在读取「$name」的直播源…$suffix"
            previewFailedUrl == selected -> "暂时无法预览「$name」的直播源，点此重试$suffix"
            else -> "预览「$name」的直播源$suffix"
        }
        return PreviewState(true, previewFailedUrl == selected, text)
    }

    /** 预览失败态下点状态条重试:先清失败标记,由页面重绘时重新发起预览 */
    fun isPreviewFailedForSelected(): Boolean = previewFailedUrl == state.selectedUrl

    fun retryPreview() {
        previewFailedUrl = null
    }

    fun exportSnapshot(): LiveSourceExporter.Snapshot {
        val history = LiveConfig.liveHistory()
        val current = SystemConfig.getLiveUrl().trim()
        val fromSubscription = LiveChannelConfigProviders.get().subscribeLiveSources.any { it.url == current }
            || previewCache.values.any { sources -> sources.any { it.url == current } }
        val origin = when {
            history.any { it.trim() == current } -> LiveSourceExporter.Origin.USER
            fromSubscription -> LiveSourceExporter.Origin.SUBSCRIPTION
            current == LiveConfig.DEFAULT_LIVE_URL -> LiveSourceExporter.Origin.DEFAULT
            else -> LiveSourceExporter.Origin.UNKNOWN
        }
        return LiveSourceExporter.Snapshot(history, LiveConfig.liveNames(),
            LiveSourceExporter.CurrentSource(current, origin))
    }

    // 只删除明确确认的地址，不把抽屉里隐藏当前项后的列表当成完整历史写回。
    fun deleteHistory(url: String): Boolean {
        val history = LiveConfig.liveHistory()
        val remaining = withoutLiveHistoryEntry(history, url)
        if (remaining.size == history.size) return true
        LiveConfig.setLiveHistory(remaining)
        LogStore.log(Category.SUBSCRIPTION, "订阅: 删除历史直播源")
        host.onLiveSourcesRenderNeeded()
        return true
    }

    /**
     * 勾选/取消一条直播源:<b>勾中即成为当前直播源</b>(直播页据此拉取);取消 = 回到"用订阅自带的直播"。
     * 内嵌分组(没有单独地址)不可选,由调用方拦掉。
     * @return 提示文案;null=目标与当前一致(无需提示,调用方仍要重绘列表)
     */
    fun applyLiveSource(url: String?): String? {
        val target = url ?: ""
        if (target == SystemConfig.getLiveUrl()) return null
        SystemConfig.setLiveUrl(target)
        state.liveSourceChanged = true
        LogStore.log(
            Category.SUBSCRIPTION,
            "订阅: 当前直播源改为 " + (if (target.isEmpty()) "订阅自带" else logSource(target))
        )
        return if (target.isEmpty()) "已改用订阅直播源" else "直播源已切换"
    }

    /**
     * 新增一条自建直播源并设为当前(与设置页改直播源同语义):
     * 记入用户历史(最多 20 条)、存下用户起的名字;地址真变了才标记待重载。
     */
    fun saveNewSource(name: String, url: String): String {
        val target = url.trim()
        val before = SystemConfig.getLiveUrl()
        SystemConfig.setLiveUrl(target)
        if (target.isNotEmpty()) {
            val history = LiveConfig.liveHistory()
            if (!history.contains(target)) history.add(0, target)
            if (history.size > 20) history.removeAt(20)
            LiveConfig.setLiveHistory(history)
            LiveConfig.setLiveName(target, name)
        }
        if (before != target) {
            state.liveSourceChanged = true
            LogStore.log(Category.SUBSCRIPTION, "订阅: 当前直播源改为 " + logSource(target))
        }
        return "设置成功"
    }

    /**
     * 编辑一条自建直播源(改名 / 改地址):
     * 地址变了就把历史里那条就地替换(新地址已在列表里则合并成一条,不留重复行),名字跟着搬过去;
     * 只有改的是**当前生效**那条时才需要重载直播配置。
     */
    fun applyEdit(entry: LiveSourceEntries.Entry, name: String, url: String): String {
        val oldUrl = entry.url
        val target = url.trim()
        if (target != oldUrl) {
            val history = LiveConfig.liveHistory()
            val oldIndex = history.indexOf(oldUrl)
            when {
                history.contains(target) -> if (oldIndex >= 0) history.removeAt(oldIndex)
                oldIndex >= 0 -> history[oldIndex] = target
                else -> history.add(0, target)
            }
            LiveConfig.setLiveHistory(history)
            LiveConfig.removeLiveName(oldUrl)
        }
        LiveConfig.setLiveName(target, name)
        if (target != oldUrl && oldUrl == SystemConfig.getLiveUrl()) {
            SystemConfig.setLiveUrl(target)
            state.liveSourceChanged = true
            LogStore.log(Category.SUBSCRIPTION, "订阅: 编辑当前直播源地址")
        } else {
            LogStore.log(Category.SUBSCRIPTION, "订阅: 编辑自建直播源")
        }
        return "已保存"
    }

    /** 多选删除自建直播源;删除正在用的那条会顺带清掉"当前直播源" */
    fun deleteSelected(targets: List<LiveSourceEntries.Entry>): String {
        val history = LiveConfig.liveHistory()
        for (entry in targets) {
            history.remove(entry.url)
            LiveConfig.removeLiveName(entry.url)
            // 删掉的正是在用的那条 → 回到"用订阅自带的直播"
            if (entry.url == SystemConfig.getLiveUrl()) SystemConfig.setLiveUrl("")
        }
        LiveConfig.setLiveHistory(history)
        state.liveSourceChanged = true
        LogStore.log(Category.SUBSCRIPTION, "订阅: 多选删除 " + targets.size + " 个自建直播源")
        return "已删除 " + targets.size + " 个直播源"
    }

    /** 删除一条订阅时:若删的是已加载订阅且当前直播源来自它,清掉"当前直播源"避免悬空 */
    fun onSubscriptionRemoved(url: String) {
        val provider = LiveChannelConfigProviders.get()
        if (provider.loadedSubscriptionUrl != url) return
        state.liveSourceChanged = true
        val selectedLiveUrl = SystemConfig.getLiveUrl()
        if (selectedLiveUrl.isNotEmpty() &&
            provider.subscribeLiveSources.any { it.url == selectedLiveUrl } &&
            !LiveConfig.liveHistory().contains(selectedLiveUrl)
        ) {
            SystemConfig.setLiveUrl("")
            LogStore.log(Category.SUBSCRIPTION, "订阅: 删除订阅后清除其直播源选中地址")
        }
    }

    /** 换订阅重启前:旧订阅独有的直播地址不能随着视频源切换变成一条"用户自建"源 */
    fun clearStaleLiveUrlBeforeRestart() {
        val provider = LiveChannelConfigProviders.get()
        val selectedLiveUrl = SystemConfig.getLiveUrl()
        if (selectedLiveUrl.isEmpty()) return
        if (provider.loadedSubscriptionUrl == state.selectedUrl) return
        if (!provider.subscribeLiveSources.any { it.url == selectedLiveUrl }) return
        if (LiveConfig.liveHistory().contains(selectedLiveUrl)) return
        if (previewCache[state.selectedUrl].orEmpty().any { it.url == selectedLiveUrl }) return
        SystemConfig.setLiveUrl("")
    }

    private fun logSource(url: String): String = when {
        url.startsWith("clan://") -> "本地订阅"
        url.startsWith("http://") || url.startsWith("https://") -> "网络订阅"
        else -> "其他来源"
    }
}

internal fun withoutLiveHistoryEntry(history: List<String>, url: String): ArrayList<String> =
    ArrayList(history).apply { remove(url) }

/** 直播源页一次重建的渲染快照(数据由协调器算好,页面只负责绑到视图) */
class LiveSourceSnapshot(
    val entries: List<LiveSourceEntries.Entry>,
    val previewVisible: Boolean,
    val previewText: String,
    val previewClickable: Boolean,
    val empty: Boolean,
    val emptyText: String
)
