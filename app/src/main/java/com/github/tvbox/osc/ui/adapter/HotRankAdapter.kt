package com.github.tvbox.osc.ui.adapter

import android.content.res.ColorStateList
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.chad.library.adapter.base.BaseQuickAdapter
import com.chad.library.adapter.base.BaseViewHolder
import com.github.tvbox.osc.R
import com.github.tvbox.osc.theme.ThemeDrawables
import com.github.tvbox.osc.theme.ThemeSweep
import com.github.tvbox.osc.util.SearchApiParsers

/**
 * 「最近热搜」条目列(固定两列,数据来自 360 影视排行 rank?cat=3)。
 *
 * 两件事在这里定死:
 *  1. **列优先排**(用户口径"先竖着排,从左到右"):左列排前半、右列排后半。
 *     RecyclerView 的网格是行优先填充的,所以每个格子显示第几条由
 *     [SearchApiParsers.columnMajorIndex] 折算(纯函数 + JVM 单测)。
 *  2. **序号牌颜色**:前三名固定色(1 `search_rank_top1` 红 / 2 `search_rank_top2` 橙 /
 *     3 `search_rank_top3` 黄,名次语义色,不随主题)+ 白字;4 名往后**去掉序号底**,
 *     数字用**文字主色 50% 透明**(用户口径)。
 */
class HotRankAdapter : BaseQuickAdapter<String, BaseViewHolder>(R.layout.item_search_hot_rank) {

    fun wordAt(displayPosition: Int): String? {
        if (displayPosition !in data.indices) return null
        val source = SearchApiParsers.columnMajorIndex(displayPosition, data.size, COLUMNS)
        return data.getOrNull(source)
    }

    override fun convert(holder: BaseViewHolder, item: String) {
        // 行优先位置 → 列优先源下标(显示顺序与序号都按源下标走)
        val source = SearchApiParsers.columnMajorIndex(holder.layoutPosition, data.size, COLUMNS)
        val word = data.getOrNull(source) ?: item
        val rank = source + 1
        holder.setText(R.id.tv_rank, rank.toString())
        holder.setText(R.id.tv_word, word)
        // RecyclerView 子项可能绕过主题注入器；复用时也要按当前主题重设词条色。
        ThemeSweep.applyTextColor(holder.getView(R.id.tv_word), R.color.text_main_half)

        val context = holder.itemView.context
        val badge = holder.getView<TextView>(R.id.tv_rank)
        val topColor = when (rank) {
            1 -> R.color.search_rank_top1
            2 -> R.color.search_rank_top2
            3 -> R.color.search_rank_top3
            else -> 0
        }
        if (topColor != 0) {
            // 前三名:固定色圆牌 + 白字
            ThemeDrawables.applyBackground(badge, R.drawable.bg_search_rank_badge)
            badge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, topColor))
            badge.setTextColor(ContextCompat.getColor(context, R.color.white))
        } else {
            // 4 名往后:**去掉序号底**,数字用「文字主色 50% 透明」(用户口径);
            // 宽度仍占 22dp(与前三名对齐成一列)。视图是复用的,这里必须显式清掉底与色。
            badge.background = null
            ThemeSweep.applyTextColor(badge, R.color.text_main_half)
        }
    }

    private companion object {
        const val COLUMNS = 2
    }
}
