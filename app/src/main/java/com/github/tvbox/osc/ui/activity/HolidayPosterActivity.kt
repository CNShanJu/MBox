package com.github.tvbox.osc.ui.activity

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.databinding.ActivityHolidayPosterBinding
import com.github.tvbox.osc.ui.adapter.HolidayPosterPagerAdapter
import com.github.tvbox.osc.ui.kit.applyNavigationBarBottomPadding
import com.github.tvbox.osc.ui.splash.HolidayPosterPage
import com.github.tvbox.osc.ui.splash.HolidayPosterSaver
import com.github.tvbox.osc.ui.splash.HolidaySplashMediaStore
import com.github.tvbox.osc.ui.splash.holidayDateLabel
import com.github.tvbox.osc.util.AppBubble
import com.github.tvbox.osc.util.HeavyTaskUtil
import com.github.tvbox.osc.util.holiday.HolidayCalendarClock
import com.github.tvbox.osc.util.holiday.HolidayCatalogRepository
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.Permission
import com.hjq.permissions.XXPermissions
import java.util.Calendar

/**
 * 节日海报大图页：从「我的」页顶部的节日名进入，按开屏同样的层级完整展示当天海报，并支持保存到相册。
 *
 * 同一天命中多个节日时一页一张，上下滑动切换；素材来源与开屏一致
 * （`splash/holiday_media.json` + 本地缓存），未缓存的直接走网络，不写开屏缓存目录。
 */
class HolidayPosterActivity : BaseVbActivity<ActivityHolidayPosterBinding>() {

    private val store by lazy { HolidaySplashMediaStore(applicationContext) }
    private val catalogRepository by lazy { HolidayCatalogRepository(applicationContext) }

    private lateinit var pagerAdapter: HolidayPosterPagerAdapter
    private lateinit var snapHelper: PagerSnapHelper
    private var pages: List<HolidayPosterPage> = emptyList()
    private var currentIndex = 0
    private var saving = false

    override fun init() {
        mBinding.titleBar.setOnBackClickListener { finish() }
        // 底部保存栏贴底，需要让出导航栏，否则按钮会被系统栏压住。
        mBinding.root.applyNavigationBarBottomPadding()
        mBinding.posterSave.setOnClickListener { onSaveClicked() }
        pagerAdapter = HolidayPosterPagerAdapter()
        snapHelper = PagerSnapHelper()
        mBinding.posterPager.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
        mBinding.posterPager.adapter = pagerAdapter
        snapHelper.attachToRecyclerView(mBinding.posterPager)
        mBinding.posterPager.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) syncCurrentPage()
            }
        })
        loadPages()
    }

    private fun loadPages() {
        val wantedId = intent.getStringExtra(EXTRA_HOLIDAY_ID)
        HeavyTaskUtil.executeBigTask {
            val catalog = runCatching { catalogRepository.getOrLoad() }.getOrNull()
            val today = HolidayCalendarClock.now()
            val matches = runCatching {
                catalog?.getMatchingHolidays(
                    today.get(Calendar.YEAR),
                    today.get(Calendar.MONTH) + 1,
                    today.get(Calendar.DAY_OF_MONTH)
                ).orEmpty()
            }.getOrDefault(emptyList())
            // 只有当天命中且素材表里有海报的节日才成页；顺序与「我的」页节日名一致（优先级降序），
            // 用户点的那条排最前。
            val ordered = matches.filter { store.hasMedia(it) }
                .sortedByDescending { it.displayPriority }
                .let { list ->
                    if (wantedId == null) list
                    else list.sortedBy { if (it.id == wantedId) 0 else 1 }
                }
            val mediaById = store.mediaEntries().associateBy { it.id }
            val loaded = ordered.mapNotNull { entry ->
                val media = mediaById[entry.id] ?: return@mapNotNull null
                HolidayPosterPage(
                    id = entry.id,
                    name = entry.name,
                    dateLabel = holidayDateLabel(entry),
                    url = media.url,
                    cachedPath = store.cachedImagePath(media),
                    anchorX = media.anchorX,
                    anchorY = media.anchorY
                )
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (loaded.isEmpty()) {
                    AppBubble.toast("今天没有可展示的节日海报")
                    finish()
                    return@runOnUiThread
                }
                pages = loaded
                currentIndex = 0
                pagerAdapter.submit(loaded)
                mBinding.posterIndicator.visibility =
                    if (loaded.size > 1) View.VISIBLE else View.GONE
                bindInfo()
            }
        }
    }

    /** 滚动停稳后按吸附到的那一页更新底部信息与保存目标。 */
    private fun syncCurrentPage() {
        val snapView = snapHelper.findSnapView(mBinding.posterPager.layoutManager) ?: return
        val position = mBinding.posterPager.getChildAdapterPosition(snapView)
        if (position == RecyclerView.NO_POSITION || position == currentIndex) return
        currentIndex = position
        bindInfo()
    }

    private fun bindInfo() {
        val page = pages.getOrNull(currentIndex) ?: return
        mBinding.posterName.text = page.name
        mBinding.posterDate.text = page.dateLabel
        mBinding.posterIndicator.text = "${currentIndex + 1} / ${pages.size}"
    }

    private fun onSaveClicked() {
        if (saving) return
        if (HolidayPosterSaver.needsLegacyPermission()) {
            // Android 9 及以下写公共相册需要存储权限；Android 10+ 走 MediaStore 不需要。
            XXPermissions.with(this)
                .permission(Permission.WRITE_EXTERNAL_STORAGE)
                .request(object : OnPermissionCallback {
                    override fun onGranted(permissions: MutableList<String>, all: Boolean) {
                        if (all) startSave() else AppBubble.toast("没有存储权限，无法保存到相册")
                    }

                    override fun onDenied(permissions: MutableList<String>, never: Boolean) {
                        AppBubble.toast("没有存储权限，无法保存到相册")
                    }
                })
            return
        }
        startSave()
    }

    private fun startSave() {
        val page = pages.getOrNull(currentIndex) ?: return
        if (saving) return
        saving = true
        val saveButton = mBinding.posterSave
        saveButton.isEnabled = false
        saveButton.text = "保存中…"
        HolidayPosterSaver.save(this, page.cachedPath, page.url, page.name) { result ->
            saving = false
            saveButton.isEnabled = true
            saveButton.text = "保存到相册"
            when (result) {
                is HolidayPosterSaver.Result.Saved ->
                    AppBubble.toast("已保存到相册：${result.fileName}")
                HolidayPosterSaver.Result.NeedsPermission ->
                    AppBubble.toast("没有存储权限，无法保存到相册")
                HolidayPosterSaver.Result.Failed ->
                    AppBubble.toast("保存失败，请稍后重试")
            }
        }
    }

    companion object {
        private const val EXTRA_HOLIDAY_ID = "com.github.tvbox.osc.HOLIDAY_POSTER_ID"

        /** 打开海报页；[holidayId] 为空时按当天命中的节日顺序展示。 */
        @JvmStatic
        fun start(context: Context, holidayId: String?) {
            context.startActivity(
                Intent(context, HolidayPosterActivity::class.java)
                    .putExtra(EXTRA_HOLIDAY_ID, holidayId)
            )
        }
    }
}
