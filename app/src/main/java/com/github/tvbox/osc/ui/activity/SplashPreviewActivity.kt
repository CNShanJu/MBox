package com.github.tvbox.osc.ui.activity

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.databinding.ActivitySplashPreviewBinding
import com.github.tvbox.osc.ui.adapter.SplashPreviewAdapter
import com.github.tvbox.osc.ui.kit.applyNavigationBarBottomPadding
import com.github.tvbox.osc.ui.splash.HolidaySplashMediaStore
import com.github.tvbox.osc.ui.splash.SplashPreviewItem
import com.github.tvbox.osc.ui.splash.SplashPosterFit
import com.github.tvbox.osc.ui.splash.holidayDateLabel
import com.github.tvbox.osc.util.AppBubble
import com.github.tvbox.osc.util.HeavyTaskUtil
import com.github.tvbox.osc.util.holiday.HolidayCalendarClock
import com.github.tvbox.osc.util.holiday.HolidayCatalogRepository
import com.squareup.picasso.Picasso
import com.squareup.picasso.Target
import java.io.File
import java.util.Calendar

/**
 * Debug 专用的节日开屏预览：逐个查看 `splash/holiday_media.json` 里的海报在开屏上的实际效果。
 *
 * 渲染层级与真实开屏一致（模糊压暗底图 → 完整居中海报），便于确认图内标题有没有被裁掉。
 * 未缓存的素材直接走网络，**不写入 `filesDir/splash_media`**，避免预览行为污染正式开屏缓存。
 */
class SplashPreviewActivity : BaseVbActivity<ActivitySplashPreviewBinding>() {

    private val store by lazy { HolidaySplashMediaStore(applicationContext) }
    private val catalogRepository by lazy { HolidayCatalogRepository(applicationContext) }

    private lateinit var adapter: SplashPreviewAdapter
    private var previewTarget: Target? = null

    /** 当前展示的素材 id；用于丢弃切换后迟到的图片回调。 */
    private var currentId: String? = null

    /** 底图回填的代数；切换素材后旧的模糊任务结果直接丢弃。 */
    override fun init() {
        mBinding.titleBar.setOnBackClickListener { finish() }
        // 底部缩略图条贴底，需要让出导航栏，否则名称一行会被盖掉。
        mBinding.root.applyNavigationBarBottomPadding()
        adapter = SplashPreviewAdapter { show(it) }
        mBinding.previewList.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        mBinding.previewList.adapter = adapter
        loadItems()
    }

    private fun loadItems() {
        HeavyTaskUtil.executeBigTask {
            val catalog = try {
                catalogRepository.getOrLoad()
            } catch (error: Throwable) {
                null
            }
            val todayIds = try {
                val now = HolidayCalendarClock.now()
                catalog?.getMatchingHolidays(
                    now.get(Calendar.YEAR),
                    now.get(Calendar.MONTH) + 1,
                    now.get(Calendar.DAY_OF_MONTH)
                )?.map { it.id }?.toSet().orEmpty()
            } catch (error: Throwable) {
                emptySet<String>()
            }
            val items = store.mediaEntries().map { media ->
                val entry = catalog?.holidays?.firstOrNull { it.id == media.id }
                SplashPreviewItem(
                    id = media.id,
                    name = media.name,
                    url = media.url,
                    dateLabel = holidayDateLabel(entry),
                    cachedPath = store.cachedImagePath(media),
                    anchorX = media.anchorX,
                    anchorY = media.anchorY
                )
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                adapter.submitList(items)
                // 默认落在"今天命中的节日"，方便一眼核对当天开屏；没有就展示第一条。
                val initial = items.firstOrNull { it.id in todayIds } ?: items.firstOrNull()
                if (initial != null) {
                    show(initial)
                } else {
                    mBinding.previewCaption.text = "素材表为空，请检查 assets/splash/holiday_media.json"
                }
            }
        }
    }

    private fun show(item: SplashPreviewItem) {
        currentId = item.id
        adapter.markSelected(item.id)
        val cacheState = if (item.cachedPath != null) "已缓存" else "未缓存（预览走网络）"
        val anchorLabel = if (item.anchorX < 0.5f) "标题左" else "标题右"
        mBinding.previewCaption.text = "${item.name} · ${item.dateLabel} · $anchorLabel · $cacheState"
        mBinding.previewPhoto.animate().cancel()
        mBinding.previewPhoto.alpha = 0f
        val target = object : Target {
            override fun onBitmapLoaded(bitmap: Bitmap, from: Picasso.LoadedFrom) {
                if (isFinishing || isDestroyed || currentId != item.id) return
                // 与开屏一致：按像素定位，按该图锚点铺满裁切。
                bitmap.density = Bitmap.DENSITY_NONE
                mBinding.previewPhoto.setImageBitmap(bitmap)
                mBinding.previewPhoto.visibility = View.VISIBLE
                SplashPosterFit.applyCover(mBinding.previewPhoto, bitmap, item.anchorX, item.anchorY)
                mBinding.previewPhoto.animate().alpha(1f).setDuration(FADE_MS).start()
            }

            override fun onBitmapFailed(error: Exception, errorDrawable: Drawable?) {
                if (isFinishing || isDestroyed || currentId != item.id) return
                mBinding.previewPhoto.setImageDrawable(null)
                AppBubble.toast("海报加载失败：${item.name}")
            }

            override fun onPrepareLoad(placeHolderDrawable: Drawable?) = Unit
        }
        previewTarget?.let { Picasso.get().cancelRequest(it) }
        previewTarget = target
        val request = item.cachedPath?.let { Picasso.get().load(File(it)) }
            ?: Picasso.get().load(item.url)
        request.into(target)
    }

    override fun onDestroy() {
        previewTarget?.let { Picasso.get().cancelRequest(it) }
        previewTarget = null
        super.onDestroy()
    }

    private companion object {
        const val FADE_MS = 180L
    }
}
