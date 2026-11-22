package com.github.tvbox.osc.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.tvbox.osc.databinding.ItemSplashPreviewBinding
import com.github.tvbox.osc.ui.splash.SplashPreviewItem
import com.squareup.picasso.Picasso
import java.io.File

/** 开屏预览页底部的素材条：缩略图 + 名称，选中项高亮。 */
internal class SplashPreviewAdapter(
    private val onSelected: (SplashPreviewItem) -> Unit
) : ListAdapter<SplashPreviewItem, SplashPreviewAdapter.Holder>(DIFF) {

    private var selectedId: String? = null

    fun markSelected(id: String) {
        val previous = selectedId
        if (previous == id) return
        selectedId = id
        indexOf(previous)?.let { notifyItemChanged(it) }
        indexOf(id)?.let { notifyItemChanged(it) }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemSplashPreviewBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    private fun indexOf(id: String?): Int? {
        if (id == null) return null
        val index = currentList.indexOfFirst { it.id == id }
        return if (index >= 0) index else null
    }

    inner class Holder(private val binding: ItemSplashPreviewBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener { selectAt(bindingAdapterPosition) }
            // 遥控器/键盘焦点落到哪一项就切到哪一张，电视上不用再按一次确认。
            binding.root.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) selectAt(bindingAdapterPosition)
            }
        }

        fun bind(item: SplashPreviewItem) {
            binding.itemName.text = item.name
            binding.root.isSelected = item.id == selectedId
            binding.itemName.setTextColor(
                binding.root.context.getColor(
                    if (item.id == selectedId) {
                        com.github.tvbox.osc.R.color.text_foreground
                    } else {
                        com.github.tvbox.osc.R.color.text_sub_foreground
                    }
                )
            )
            binding.itemThumb.setImageDrawable(null)
            val request = item.cachedPath?.let { Picasso.get().load(File(it)) }
                ?: Picasso.get().load(item.url)
            request.resize(THUMB_SIZE_PX, THUMB_SIZE_PX).centerCrop().into(binding.itemThumb)
        }

        private fun selectAt(position: Int) {
            if (position == RecyclerView.NO_POSITION) return
            onSelected(getItem(position))
        }
    }

    private companion object {
        /** 缩略图解码尺寸（px）：76dp 在 2x/3x 屏约 152~228px，取 200 兼顾清晰与内存。 */
        const val THUMB_SIZE_PX = 200

        val DIFF = object : DiffUtil.ItemCallback<SplashPreviewItem>() {
            override fun areItemsTheSame(oldItem: SplashPreviewItem, newItem: SplashPreviewItem) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: SplashPreviewItem, newItem: SplashPreviewItem) =
                oldItem == newItem
        }
    }
}
