package com.github.tvbox.osc.ui.adapter

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.tvbox.osc.databinding.ItemHolidayPosterPageBinding
import com.github.tvbox.osc.ui.splash.HolidayPosterPage
import com.github.tvbox.osc.ui.splash.SplashPosterFit
import com.squareup.picasso.Callback
import com.squareup.picasso.Picasso
import java.io.File

/**
 * 节日海报页的翻页列表：一页一张海报铺满整页，同日多个节日可上下滑动切换。
 *
 * 每页只发一个 Picasso 请求：原图按像素解码，再由 [SplashPosterFit] 按该图锚点铺满裁切，
 * 保证顶部标题不会被裁掉（标题在左/右每张不同）。
 */
internal class HolidayPosterPagerAdapter :
    RecyclerView.Adapter<HolidayPosterPagerAdapter.Holder>() {

    private val pages = ArrayList<HolidayPosterPage>()

    /** 一次性填充：页数通常 1~3，之后不增量更新。 */
    fun submit(list: List<HolidayPosterPage>) {
        pages.clear()
        pages.addAll(list)
        notifyDataSetChanged()
    }

    fun pageAt(position: Int): HolidayPosterPage? = pages.getOrNull(position)

    override fun getItemCount(): Int = pages.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        ItemHolidayPosterPageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
    )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(pages[position])
    }

    class Holder(private val binding: ItemHolidayPosterPageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        /** 当前绑定的素材 id：滑动复用后丢弃上一页迟到的回调。 */
        private var boundId: String? = null

        fun bind(page: HolidayPosterPage) {
            boundId = page.id
            binding.pageLoading.visibility = View.VISIBLE
            binding.pagePhoto.setImageDrawable(null)

            // 已缓存读本地文件，未缓存直接走网络（预览用，不写开屏缓存目录）。
            // 用 ARGB_8888 解码：Picasso 全局是 RGB_565（省内存），但水墨海报的浅色渐变
            // 在 16 位色深下会出色带、整体偏色，看图页必须还原原图颜色。
            val request = page.cachedPath?.let { Picasso.get().load(File(it)) }
                ?: Picasso.get().load(page.url)
            request.config(Bitmap.Config.ARGB_8888)
                .into(binding.pagePhoto, object : Callback {
                override fun onSuccess() {
                    if (boundId != page.id || bindingAdapterPosition == RecyclerView.NO_POSITION) return
                    binding.pageLoading.visibility = View.GONE
                    val bitmap = (binding.pagePhoto.drawable as? BitmapDrawable)?.bitmap
                    if (bitmap != null) {
                        SplashPosterFit.applyCover(binding.pagePhoto, bitmap, page.anchorX, page.anchorY)
                    }
                    binding.pagePhoto.alpha = 0f
                    binding.pagePhoto.animate().alpha(1f).setDuration(FADE_MS).start()
                }

                override fun onError(error: Exception?) {
                    if (boundId != page.id) return
                    binding.pageLoading.visibility = View.GONE
                }
            })
        }
    }

    private companion object {
        const val FADE_MS = 180L
    }
}
