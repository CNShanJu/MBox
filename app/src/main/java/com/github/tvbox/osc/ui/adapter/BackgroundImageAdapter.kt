package com.github.tvbox.osc.ui.adapter

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.chad.library.adapter.base.BaseQuickAdapter
import com.chad.library.adapter.base.BaseViewHolder
import com.github.tvbox.osc.R
import com.github.tvbox.osc.util.PicassoLoad
import com.github.tvbox.osc.util.cache.BackgroundCache
import java.util.ArrayList

/**
 * 「本地缓存」图片网格适配器:展示应用私有目录里保存的背景图。
 * <p>多选只针对「副本」(可删除):使用中的底图与被主题引用的图在选中态下保留角标、不可勾选。
 * 普通态点图由 Activity 处理(设为背景);角标区分 使用中 / 主题 / 副本。
 */
class BackgroundImageAdapter :
    BaseQuickAdapter<BackgroundCache.Image, BaseViewHolder>(R.layout.item_background_cache, ArrayList()) {
    var selectMode = false
        private set

    /** 瀑布流列数(由页面按屏幕宽度自适应设置;转屏时同步更新) */
    var spanCount = 3
        set(value) {
            if (field != value) {
                field = if (value < 1) 1 else value
                notifyDataSetChanged()
            }
        }

    private val selectedPaths = HashSet<String>()

    override fun convert(helper: BaseViewHolder, item: BackgroundCache.Image) {
        val ivThumb = helper.getView<ImageView>(R.id.iv_thumb)
        // 瀑布流:等比缩放,高 = 列宽 × 原始高宽比(夹在 0.6~1.8 之间防极端长图撑爆/压扁)
        val ratio = item.aspectRatio.coerceIn(0.6f, 1.8f)
        val lp = ivThumb.layoutParams
        lp.height = Math.round(columnContentWidth() * ratio)
        ivThumb.layoutParams = lp
        PicassoLoad.intoFile(ivThumb, item.file)

        val tvBadge = helper.getView<TextView>(R.id.tv_badge)
        val ivSelect = helper.getView<ImageView>(R.id.iv_select)
        if (selectMode && item.deletable) {
            // 可删副本:角标让位给多选勾
            tvBadge.visibility = View.GONE
            ivSelect.visibility = View.VISIBLE
            ivSelect.setImageResource(
                if (selectedPaths.contains(item.file.absolutePath))
                    R.drawable.ic_select_checked else R.drawable.ic_select_ring
            )
        } else {
            tvBadge.visibility = View.VISIBLE
            tvBadge.text = when (item.kind) {
                BackgroundCache.Kind.CURRENT -> "使用中"
                BackgroundCache.Kind.THEME -> "主题"
                BackgroundCache.Kind.ORPHAN -> "副本"
            }
            ivSelect.visibility = View.GONE
        }
    }

    fun enterSelectMode(position: Int) {
        val item = getItem(position) ?: return
        selectMode = true
        if (item.deletable) selectedPaths.add(item.file.absolutePath)
        notifyDataSetChanged()
    }

    fun exitSelectMode() {
        selectMode = false
        selectedPaths.clear()
        notifyDataSetChanged()
    }

    fun toggleSelection(position: Int) {
        val item = getItem(position) ?: return
        if (!item.deletable) return
        val key = item.file.absolutePath
        if (!selectedPaths.add(key)) selectedPaths.remove(key)
        notifyItemChanged(position)
    }

    fun selectAll() {
        for (item in data) {
            if (item.deletable) selectedPaths.add(item.file.absolutePath)
        }
        notifyDataSetChanged()
    }

    fun cancelAllSelection() {
        if (selectedPaths.isEmpty()) return
        selectedPaths.clear()
        notifyDataSetChanged()
    }

    fun selectedCount(): Int = selectedPaths.size

    fun selectedImages(): List<BackgroundCache.Image> =
        data.filter { selectedPaths.contains(it.file.absolutePath) }

    /**
     * 单列内容宽度:网格左右内边距 10dp×2 + 条目自身 3dp 外边距×2 后的净宽。
     * (列宽由 GridLayoutManager 按屏宽均分;这里用屏宽反推,首绑时视图还没量出来也能给对高度)
     */
    private fun columnContentWidth(): Int {
        val dm = mContext.resources.displayMetrics
        val dp = { v: Int -> Math.round(v * dm.density) }
        val gridWidth = com.blankj.utilcode.util.ScreenUtils.getScreenWidth() - dp(10) * 2
        val columnWidth = gridWidth / spanCount
        return Math.max(dp(60), columnWidth - dp(6))
    }
}
