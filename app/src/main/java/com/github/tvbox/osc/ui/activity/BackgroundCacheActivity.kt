package com.github.tvbox.osc.ui.activity

import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.chad.library.adapter.base.BaseQuickAdapter
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.databinding.ActivityBackgroundCacheBinding
import com.github.tvbox.osc.ui.adapter.BackgroundImageAdapter
import com.github.tvbox.osc.ui.dialog.ConfirmDialog
import com.github.tvbox.osc.ui.dialog.TextTipDialog
import com.github.tvbox.osc.ui.kit.SelectActionBar
import com.github.tvbox.osc.util.AppBubble
import com.github.tvbox.osc.util.FastClickCheckUtil
import com.github.tvbox.osc.util.HeavyTaskUtil
import com.github.tvbox.osc.util.Utils
import com.github.tvbox.osc.util.cache.BackgroundCache
import com.lxj.xpopup.XPopup
import java.lang.ref.WeakReference

/**
 * 背景图「本地缓存」二级页(入口:背景图设置页标题栏右侧「本地仓」图标)。
 * <p>展示应用私有目录里保存的所有背景图(当前底图 / 主题图 / 遗留副本):
 * <ul>
 *   <li><b>点击</b>:弹「是否使用该图作为背景图」,点是 → 把路径经 setResult 交回背景设置页
 *       (由设置页走「更换图片」的收编函数落地);</li>
 *   <li><b>长按多选</b>:只可勾选「副本」删除;使用中的底图与被主题引用的图不让选(点/长按给提示)。</li>
 * </ul>
 */
class BackgroundCacheActivity : BaseVbActivity<ActivityBackgroundCacheBinding>() {

    companion object {
        /** setResult 里带回选中图片的绝对路径 */
        const val EXTRA_RESULT_PATH = "bg_cache_result_path"
        /** 父页打开缓存时的草稿保护快照；配置尚未保存也不可删。 */
        const val EXTRA_PROTECTED_PATHS = "bg_cache_protected_paths"
    }

    private val cache by lazy {
        BackgroundCache(applicationContext, intent?.getStringArrayListExtra(EXTRA_PROTECTED_PATHS).orEmpty())
    }
    private val adapter = BackgroundImageAdapter()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var deleteAction: TextView
    private var deleting = false
    @Volatile private var dataEpoch = 0

    override fun init() {
        mBinding.titleBar.setOnBackClickListener { onBackPressed() }
        mBinding.mGridView.setHasFixedSize(false)
        applyGridSpan()
        mBinding.mGridView.adapter = adapter

        // 使用说明(与订阅管理同款图标/动作):图标在布局里(iv_tip),点击 = 底部抽屉讲清三类图与页面用法
        mBinding.ivTip.setOnClickListener {
            showUseTip()
        }

        adapter.onItemClickListener = BaseQuickAdapter.OnItemClickListener { _, view, position ->
            if (deleting) return@OnItemClickListener
            val image = adapter.data.getOrNull(position) ?: return@OnItemClickListener
            if (adapter.selectMode) {
                if (image.deletable) {
                    adapter.toggleSelection(position)
                    updateDeleteAction()
                } else {
                    AppBubble.toast(when (image.kind) {
                        BackgroundCache.Kind.CURRENT -> "当前使用或编辑中的图片，不能删除"
                        BackgroundCache.Kind.THEME -> "该图被主题使用，不能删除"
                        else -> "该图不能删除"
                    })
                }
                return@OnItemClickListener
            }
            FastClickCheckUtil.check(view)
            confirmUse(image)
        }

        adapter.onItemLongClickListener = BaseQuickAdapter.OnItemLongClickListener { _, _, position ->
            if (deleting) return@OnItemLongClickListener true
            val image = adapter.data.getOrNull(position) ?: return@OnItemLongClickListener true
            if (!image.deletable) {
                AppBubble.toast(when (image.kind) {
                    BackgroundCache.Kind.CURRENT -> "当前使用或编辑中的图片，不能删除"
                    BackgroundCache.Kind.THEME -> "该图被主题使用，不能删除"
                    else -> "该图不能删除"
                })
                return@OnItemLongClickListener true
            }
            if (!adapter.selectMode) adapter.enterSelectMode(position)
            else adapter.toggleSelection(position)
            mBinding.selectActionBar.visibility = View.VISIBLE
            updateDeleteAction()
            true
        }

        mBinding.selectActionBar.addAction("全选", SelectActionBar.Kind.NORMAL) { view ->
            if (deleting) return@addAction
            FastClickCheckUtil.check(view)
            adapter.selectAll()
            updateDeleteAction()
        }
        deleteAction = mBinding.selectActionBar.addAction("删除", SelectActionBar.Kind.DANGER) { view ->
            FastClickCheckUtil.check(view)
            confirmDeleteSelection()
        }
        mBinding.selectActionBar.addAction("取消全选", SelectActionBar.Kind.NORMAL) { view ->
            if (deleting) return@addAction
            FastClickCheckUtil.check(view)
            adapter.cancelAllSelection()
            updateDeleteAction()
        }
        updateDeleteAction()
    }

    override fun onResume() {
        super.onResume()
        if (!deleting && !adapter.selectMode) refresh()
    }

    override fun onDestroy() {
        dataEpoch++
        super.onDestroy()
    }

    /** 点图 → 是否设为背景 → 是则把路径交回上一级(背景设置页负责落盘收编) */
    private fun confirmUse(image: BackgroundCache.Image) {
        ConfirmDialog.show(this, "设为背景图", "是否使用这张图作为背景图？", "使用", {
            setResult(RESULT_OK, android.content.Intent().putExtra(EXTRA_RESULT_PATH, image.file.absolutePath))
            finish()
        })
    }

    /** 瀑布流列数:单卡最大宽度与观看历史海报同一口径(190dp),屏越宽列越多 */
    private fun applyGridSpan() {
        val span = Utils.getAdaptiveGridSpan(Utils.GRID_CARD_MAX_WIDTH_DP)
        mBinding.mGridView.layoutManager = StaggeredGridLayoutManager(span, StaggeredGridLayoutManager.VERTICAL)
        adapter.spanCount = span
    }

    /** 使用说明抽屉:三类图各是什么、页面怎么用、设为背景后还要点「确认背景」才生效 */
    private fun showUseTip() {
        val content = "本页展示应用内保存的全部背景图，分为三类：\n\n" +
                "· 使用中：当前生效或上一页正在编辑的背景图，不可删除；\n" +
                "· 主题：被某个自定义主题用到的图，删除主题前会一直保留，也不可删除；\n" +
                "· 副本：换图/恢复默认后遗留的旧图，可以长按多选删除释放空间。\n\n" +
                "点击任意一张图可把它设为背景图（确认后回到上一页预览，" +
                "还需点「确认背景」才会生效）；长按可进入多选，只可勾选「副本」删除。"
        XPopup.Builder(this).asCustom(TextTipDialog(this, "本地缓存说明", content)).show()
    }

    private fun refresh() {
        val epoch = ++dataEpoch
        val pageRef = WeakReference(this)
        val cache = this.cache
        val handler = mainHandler
        HeavyTaskUtil.getBigTaskExecutorService().execute {
            if (pageRef.get()?.dataEpoch != epoch) return@execute
            val images = runCatching { cache.listImages() }
            handler.post {
                val page = pageRef.get() ?: return@post
                if (epoch != page.dataEpoch || page.isFinishing || page.isDestroyed) return@post
                page.adapter.exitSelectMode()
                page.adapter.setNewData(images.getOrElse { ArrayList() })
                page.mBinding.selectActionBar.visibility = View.GONE
                page.updateEmptyState()
                page.updateDeleteAction()
            }
        }
    }

    private fun updateEmptyState() {
        val empty = adapter.data.isEmpty()
        mBinding.mGridView.visibility = if (empty) View.GONE else View.VISIBLE
        mBinding.llEmpty.root.visibility = if (empty) View.VISIBLE else View.GONE
        mBinding.topTip.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun updateDeleteAction() {
        mBinding.selectActionBar.setActionEnabled(
            deleteAction, !deleting && adapter.selectedCount() > 0
        )
    }

    private fun confirmDeleteSelection() {
        if (deleting) return
        val selected = adapter.selectedImages()
        if (selected.isEmpty()) return
        ConfirmDialog.showDanger(this, "删除副本", "确定删除所选 ${selected.size} 张背景图副本吗？此操作不可恢复。", "删除", {
            if (isFinishing || isDestroyed || deleting) return@showDanger
            deleting = true
            val epoch = ++dataEpoch
            updateDeleteAction()
            val pageRef = WeakReference(this)
            val cache = this.cache
            val handler = mainHandler
            HeavyTaskUtil.getBigTaskExecutorService().execute {
                val removed = runCatching {
                    cache.delete(selected) { pageRef.get()?.dataEpoch != epoch }
                }
                handler.post {
                    val page = pageRef.get() ?: return@post
                    if (epoch != page.dataEpoch || page.isFinishing || page.isDestroyed) return@post
                    page.deleting = false
                    removed.onSuccess { n ->
                        if (n == selected.size) AppBubble.toast("已删除 $n 张副本")
                        else AppBubble.toast("已删除 $n 张，其余图片已受保护、不存在或未能删除")
                        page.refresh()
                    }.onFailure {
                        AppBubble.toast("删除未全部完成，请刷新后重试")
                        page.refresh()
                    }
                }
            }
        })
    }

    override fun onBackPressed() {
        if (deleting) return
        if (adapter.selectMode) {
            if (adapter.selectedCount() > 0) adapter.cancelAllSelection()
            else {
                adapter.exitSelectMode()
                mBinding.selectActionBar.visibility = View.GONE
            }
            updateDeleteAction()
        } else {
            super.onBackPressed()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyGridSpan()
    }
}
