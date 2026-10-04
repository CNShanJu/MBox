package com.github.tvbox.osc.ui.activity

import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.os.Handler
import android.os.Looper
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.databinding.ActivityCacheManagementBinding
import com.github.tvbox.osc.databinding.ItemCacheCategoryBinding
import com.github.tvbox.osc.ui.dialog.ConfirmDialog
import com.github.tvbox.osc.ui.dialog.TextTipDialog
import com.github.tvbox.osc.ui.kit.SelectActionBar
import com.github.tvbox.osc.util.AppBubble
import com.github.tvbox.osc.util.HeavyTaskUtil
import com.github.tvbox.osc.util.cache.CacheCatalog
import com.github.tvbox.osc.util.cache.CacheSizeText
import com.lxj.xpopup.XPopup
import java.lang.ref.WeakReference

/** 可逐项清理的缓存页；目录扫描和删除都交给共享后台执行器。 */
class CacheManagementActivity : BaseVbActivity<ActivityCacheManagementBinding>() {
    private val catalog by lazy { CacheCatalog(applicationContext) }
    private val selectedIds = linkedSetOf<String>()
    private var snapshot: CacheCatalog.Snapshot? = null
    private var requestEpoch = 0
    private var busy = false
    private var selectMode = false
    private lateinit var selectAllAction: TextView
    private lateinit var deleteAction: TextView
    private lateinit var cancelAllAction: TextView

    override fun init() {
        mBinding.titleBar.setOnBackClickListener { onBackPressed() }
        selectAllAction = mBinding.selectActionBar.addAction("全选", SelectActionBar.Kind.NORMAL) {
            selectAll()
        }
        deleteAction = mBinding.selectActionBar.addAction("删除", SelectActionBar.Kind.DANGER) {
            confirmClear(selectedIds.toSet())
        }
        cancelAllAction = mBinding.selectActionBar.addAction("取消全选", SelectActionBar.Kind.NORMAL) {
            cancelAllSelection()
        }
        updateSelection()
    }

    override fun onResume() {
        super.onResume()
        // A clear already computes its own post-delete snapshot. Starting a scan here would
        // invalidate its epoch and could display a pre-delete result while deletion is running.
        if (!busy) refresh()
    }

    override fun onDestroy() {
        requestEpoch++
        super.onDestroy()
    }

    private fun refresh() {
        val epoch = ++requestEpoch
        busy = true
        updateSelection()
        val cacheCatalog = catalog
        val pageRef = WeakReference(this)
        HeavyTaskUtil.getBigTaskExecutorService().execute {
            val result = runCatching { cacheCatalog.scan() }
            Handler(Looper.getMainLooper()).post {
                val page = pageRef.get() ?: return@post
                if (!page.accept(epoch)) return@post
                page.busy = false
                result.onSuccess { page.render(it) }
                    .onFailure {
                        page.mBinding.tvTotalSize.text = "读取失败"
                        page.mBinding.tvSizeDetail.text = "请返回后重试"
                        AppBubble.toast("缓存大小读取失败")
                    }
                page.updateSelection()
            }
        }
    }

    private fun accept(epoch: Int): Boolean =
        epoch == requestEpoch && !isFinishing && !isDestroyed

    private fun render(data: CacheCatalog.Snapshot) {
        snapshot = data
        selectedIds.retainAll(data.entries.filter { it.clearable && it.sizeBytes > 0 }.map { it.id }.toSet())
        mBinding.tvTotalSize.text = (if (data.logSizeUnavailable) "已知 " else "") +
            CacheSizeText.format(data.totalBytes)
        mBinding.tvSizeDetail.text = if (data.logSizeUnavailable) {
            "日志大小读取失败，未计入总量；其他分类仍可清理"
        } else if (data.totalBytes == 0L) {
            "暂无可统计的缓存和日志；下载、收藏和观看记录不计入"
        } else {
            "可清理 ${CacheSizeText.format(data.clearableBytes)} · 必要 ${CacheSizeText.format(data.protectedBytes)}"
        }
        mBinding.categoryList.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (entry in data.entries) {
            val row = ItemCacheCategoryBinding.inflate(inflater, mBinding.categoryList, false)
            row.categoryTitle.text = entry.title
            row.categoryDescription.text = entry.description
            row.categorySize.text = if (data.logSizeUnavailable && entry.id == CacheCatalog.LOGS) {
                "读取失败"
            } else {
                CacheSizeText.format(entry.sizeBytes) + if (entry.clearable) "" else " · 保留"
            }
            val available = entry.clearable && entry.sizeBytes > 0
            row.categoryCheckbox.visibility = if (selectMode && available) View.VISIBLE else View.GONE
            row.categoryCheckbox.isChecked = selectedIds.contains(entry.id)
            row.root.isEnabled = !busy
            row.root.setOnClickListener {
                if (busy) return@setOnClickListener
                if (!selectMode || !available) {
                    showCategoryTip(entry)
                    return@setOnClickListener
                }
                if (!selectedIds.add(entry.id)) selectedIds.remove(entry.id)
                row.categoryCheckbox.isChecked = selectedIds.contains(entry.id)
                updateSelection()
            }
            row.root.setOnLongClickListener {
                if (busy) return@setOnLongClickListener true
                if (!available) {
                    showCategoryTip(entry)
                    return@setOnLongClickListener true
                }
                if (!selectMode) selectMode = true
                selectedIds.add(entry.id)
                render(data)
                true
            }
            mBinding.categoryList.addView(row.root, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        updateSelection()
    }

    private fun showCategoryTip(entry: CacheCatalog.Entry) {
        val content = when {
            !entry.clearable -> entry.description
            entry.id == CacheCatalog.TEMP_FILES && entry.sizeBytes <= 0 ->
                "导入导出工作文件通常在操作结束后自动删除；当前没有超过 24 小时的可清理副本。"
            entry.id == CacheCatalog.LOGS && entry.sizeBytes <= 0 ->
                "业务日志记录与错误日志文件按内容约大小统计。当前没有可清理的日志；新日志可能继续产生。"
            entry.sizeBytes <= 0 -> "${entry.description}\n\n当前没有可清理的磁盘缓存。"
            entry.id == CacheCatalog.LOGS ->
                "${entry.description}\n\n清理会移除业务日志记录和错误日志文件，历史排障信息无法恢复；后续新日志仍可能产生。长按此项可选择清理。"
            entry.id == CacheCatalog.TEMP_FILES ->
                "${entry.description}\n\n只清理超过 24 小时的副本。已保存到相册或选定位置的正式文件不会删除。长按此项可选择清理。"
            else -> "${entry.description}\n\n长按此项可选择清理。"
        }
        XPopup.Builder(this).asCustom(TextTipDialog(this, entry.title, content)).show()
    }

    private fun selectAll() {
        if (busy || !selectMode) return
        val available = snapshot?.entries?.filter { it.clearable && it.sizeBytes > 0 }?.map { it.id }
            ?: return
        if (available.isEmpty()) return
        selectedIds.addAll(available)
        snapshot?.let(::render)
    }

    private fun cancelAllSelection() {
        if (busy || !selectMode || selectedIds.isEmpty()) return
        selectedIds.clear()
        snapshot?.let(::render)
    }

    private fun updateSelection() {
        val count = selectedIds.size
        val bytes = snapshot?.entries?.filter { selectedIds.contains(it.id) }?.sumOf { it.sizeBytes } ?: 0L
        mBinding.tvSelection.text = if (!selectMode) "存储分类 · 长按可选择清理" else
            "已选 $count 项 · ${CacheSizeText.format(bytes)}"
        val available = snapshot?.entries?.filter { it.clearable && it.sizeBytes > 0 }?.map { it.id }
            ?: emptyList()
        mBinding.selectActionBar.visibility = if (selectMode) View.VISIBLE else View.GONE
        mBinding.selectActionBar.setActionEnabled(selectAllAction,
            !busy && available.isNotEmpty() && !selectedIds.containsAll(available))
        mBinding.selectActionBar.setActionEnabled(deleteAction, !busy && count > 0)
        mBinding.selectActionBar.setActionEnabled(cancelAllAction, !busy && count > 0)
    }

    private fun confirmClear(ids: Set<String>) {
        if (busy || ids.isEmpty()) return
        val entries = snapshot?.entries?.filter { ids.contains(it.id) && it.clearable && it.sizeBytes > 0 }
            ?: return
        if (entries.isEmpty()) return
        val validIds = entries.map { it.id }.toSet()
        val names = entries.joinToString("、") { "「${it.title}」" }
        val size = CacheSizeText.format(entries.sumOf { it.sizeBytes })
        val impact = buildString {
            if (validIds.contains(CacheCatalog.IMAGE_HTTP)) append("\n图片清理后会重新加载。")
            if (validIds.contains(CacheCatalog.TEMP_FILES)) append("\n旧导出文件的分享链接可能失效。")
            if (validIds.contains(CacheCatalog.LOGS))
                append("\n将清空业务日志记录、本应用错误日志文件及旧版日志残留，历史排障信息无法恢复；新日志仍可能继续产生。")
        }
        val sizeLabel = if (validIds.contains(CacheCatalog.LOGS)) "内容约 $size" else size
        ConfirmDialog.showDanger(this, "清除所选数据", "确定清除$names（$sizeLabel）吗？$impact", "清除", {
            clear(validIds)
        })
    }

    private fun clear(ids: Set<String>) {
        val epoch = ++requestEpoch
        busy = true
        updateSelection()
        val cacheCatalog = catalog
        val pageRef = WeakReference(this)
        HeavyTaskUtil.getBigTaskExecutorService().execute {
            val result = runCatching { cacheCatalog.clear(ids) }
            Handler(Looper.getMainLooper()).post {
                val page = pageRef.get() ?: return@post
                if (!page.accept(epoch)) return@post
                page.busy = false
                result.onSuccess {
                    page.selectedIds.removeAll(it.clearedIds.toSet())
                    if (it.failures.isEmpty()) page.selectMode = false
                    page.render(it.after)
                    AppBubble.toast(if (it.failures.isEmpty()) "所选数据已清除" else "部分数据清除失败，请重试")
                }.onFailure {
                    AppBubble.toast("清除失败，请重试")
                    page.refresh()
                }
                page.updateSelection()
            }
        }
    }

    override fun onBackPressed() {
        if (selectMode && !busy) {
            if (selectedIds.isNotEmpty()) {
                cancelAllSelection()
            } else {
                selectMode = false
                snapshot?.let(::render)
                updateSelection()
            }
        } else {
            super.onBackPressed()
        }
    }
}
