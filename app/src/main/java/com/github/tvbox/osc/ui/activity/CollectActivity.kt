package com.github.tvbox.osc.ui.activity

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.chad.library.adapter.base.BaseQuickAdapter
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.databinding.ActivityCollectBinding
import com.github.tvbox.osc.log.Category
import com.github.tvbox.osc.log.LogStore
import com.github.tvbox.osc.repo.HistoryRepositories
import com.github.tvbox.osc.spiderapi.SourceConfigProviders
import com.github.tvbox.osc.ui.adapter.CollectAdapter
import com.github.tvbox.osc.ui.dialog.ConfirmDialog
import com.github.tvbox.osc.ui.kit.SelectActionBar
import com.github.tvbox.osc.util.AppBubble
import com.github.tvbox.osc.util.FastClickCheckUtil
import com.github.tvbox.osc.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CollectActivity : BaseVbActivity<ActivityCollectBinding>() {

    private val collectAdapter = CollectAdapter()
    private lateinit var deleteAction: TextView
    private var deleting = false

    override fun init() {
        initView()
    }

    private fun initView() {
        mBinding.titleBar.setOnBackClickListener { onBackPressed() }
        mBinding.mGridView.setHasFixedSize(true)
        mBinding.mGridView.layoutManager = GridLayoutManager(
            this, Utils.getAdaptiveGridSpan(Utils.GRID_CARD_MAX_WIDTH_DP)
        )
        mBinding.mGridView.adapter = collectAdapter

        collectAdapter.onItemLongClickListener = BaseQuickAdapter.OnItemLongClickListener { _, _, position ->
            if (!deleting) {
                if (!collectAdapter.isSelectMode) collectAdapter.enterSelectMode(position)
                else collectAdapter.selectItem(position)
                mBinding.selectActionBar.visibility = View.VISIBLE
                updateDeleteAction()
            }
            true
        }
        collectAdapter.onItemClickListener = BaseQuickAdapter.OnItemClickListener { _, view, position ->
            if (deleting) return@OnItemClickListener
            if (collectAdapter.isSelectMode) {
                collectAdapter.toggleSelection(position)
                updateDeleteAction()
                return@OnItemClickListener
            }
            FastClickCheckUtil.check(view)
            val item = collectAdapter.data.getOrNull(position) ?: return@OnItemClickListener
            if (SourceConfigProviders.get().getSource(item.sourceKey) != null) {
                val bundle = Bundle()
                bundle.putString("id", item.vodId)
                bundle.putString("sourceKey", item.sourceKey)
                bundle.putString("vodName", item.name)
                jumpActivity(DetailActivity::class.java, bundle)
            } else {
                val intent = Intent(mContext, FastSearchActivity::class.java)
                intent.putExtra("title", item.name)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                startActivity(intent)
            }
        }

        mBinding.selectActionBar.addAction("全选", SelectActionBar.Kind.NORMAL) { view ->
            if (deleting) return@addAction
            FastClickCheckUtil.check(view)
            collectAdapter.selectAll()
            updateDeleteAction()
        }
        deleteAction = mBinding.selectActionBar.addAction("删除", SelectActionBar.Kind.DANGER) { view ->
            FastClickCheckUtil.check(view)
            confirmDeleteSelection()
        }
        mBinding.selectActionBar.addAction("取消全选", SelectActionBar.Kind.NORMAL) { view ->
            if (deleting) return@addAction
            FastClickCheckUtil.check(view)
            collectAdapter.cancelAllSelection()
            updateDeleteAction()
        }
        updateDeleteAction()
    }

    private fun updateDeleteAction() {
        mBinding.selectActionBar.setActionEnabled(
            deleteAction, !deleting && collectAdapter.selectedCount() > 0
        )
    }

    private fun confirmDeleteSelection() {
        if (deleting) return
        val selectedIds = collectAdapter.selectedIdSnapshot()
        if (selectedIds.isEmpty()) return
        ConfirmDialog.showDanger(
            this, "提示", "确定移除所选 ${selectedIds.size} 个收藏吗？", "删除", {
                deleting = true
                updateDeleteAction()
                showLoadingDialog()
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val repository = HistoryRepositories.collect()
                        selectedIds.forEach(repository::deleteById)
                        val remaining = ArrayList(repository.query())
                        withContext(Dispatchers.Main) {
                            collectAdapter.exitSelectMode()
                            collectAdapter.setNewData(remaining)
                            mBinding.selectActionBar.visibility = View.GONE
                            updateEmptyState()
                            LogStore.log(Category.SYSTEM, "移除收藏 ${selectedIds.size} 项")
                        }
                    } catch (error: Exception) {
                        // 已成功删除的记录不能在异常后继续显示为仍被收藏。
                        val remaining = runCatching {
                            ArrayList(HistoryRepositories.collect().query())
                        }.getOrNull()
                        withContext(Dispatchers.Main) {
                            if (remaining != null) {
                                collectAdapter.exitSelectMode()
                                collectAdapter.setNewData(remaining)
                                mBinding.selectActionBar.visibility = View.GONE
                                updateEmptyState()
                            }
                            AppBubble.toast("删除收藏未完成，请重试")
                        }
                    } finally {
                        withContext(Dispatchers.Main) {
                            deleting = false
                            dismissLoadingDialog()
                            updateDeleteAction()
                        }
                    }
                }
            }
        )
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch(Dispatchers.IO) {
            val items = ArrayList(HistoryRepositories.collect().query())
            withContext(Dispatchers.Main) {
                if (deleting || collectAdapter.isSelectMode) return@withContext
                collectAdapter.exitSelectMode()
                collectAdapter.setNewData(items)
                mBinding.selectActionBar.visibility = View.GONE
                updateEmptyState()
                updateDeleteAction()
            }
        }
    }

    private fun updateEmptyState() {
        val empty = collectAdapter.data.isEmpty()
        mBinding.mGridView.visibility = if (empty) View.GONE else View.VISIBLE
        mBinding.llEmpty.root.visibility = if (empty) View.VISIBLE else View.GONE
        mBinding.topTip.visibility = if (empty) View.GONE else View.VISIBLE
    }

    override fun onBackPressed() {
        if (deleting) return
        if (collectAdapter.isSelectMode) {
            if (collectAdapter.selectedCount() > 0) collectAdapter.cancelAllSelection()
            else {
                collectAdapter.exitSelectMode()
                mBinding.selectActionBar.visibility = View.GONE
            }
            updateDeleteAction()
        } else {
            super.onBackPressed()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val layoutManager = mBinding.mGridView.layoutManager
        if (layoutManager is GridLayoutManager) {
            layoutManager.spanCount = Utils.getAdaptiveGridSpan(Utils.GRID_CARD_MAX_WIDTH_DP)
        }
    }
}
