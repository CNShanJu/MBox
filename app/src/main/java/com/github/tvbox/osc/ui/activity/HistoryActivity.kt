package com.github.tvbox.osc.ui.activity

import android.content.res.Configuration
import android.view.View
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.chad.library.adapter.base.BaseQuickAdapter
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.config.SystemConfig
import com.github.tvbox.osc.databinding.ActivityHistoryBinding
import com.github.tvbox.osc.log.Category
import com.github.tvbox.osc.log.LogStore
import com.github.tvbox.osc.repo.HistoryRepositories
import com.github.tvbox.osc.ui.adapter.HistoryAdapter
import com.github.tvbox.osc.ui.dialog.ConfirmDialog
import com.github.tvbox.osc.ui.kit.SelectActionBar
import com.github.tvbox.osc.util.AppBubble
import com.github.tvbox.osc.util.FastClickCheckUtil
import com.github.tvbox.osc.util.HistoryEntryNavigator
import com.github.tvbox.osc.util.HistoryHelper
import com.github.tvbox.osc.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HistoryActivity : BaseVbActivity<ActivityHistoryBinding>() {
    private val historyAdapter = HistoryAdapter()
    private lateinit var deleteAction: TextView
    private var deleting = false
    private var dataEpoch = 0

    override fun init() {
        initView()
    }

    override fun onResume() {
        super.onResume()
        if (!deleting && !historyAdapter.isSelectMode) initData()
    }

    private fun initView() {
        mBinding.titleBar.setOnBackClickListener { onBackPressed() }
        // 空态使用显式视图(与订阅/下载页统一),不再依赖 LoadSir 注册
        mBinding.mGridView.setHasFixedSize(true)
        // 列数自适应:单卡宽度不超过 GRID_CARD_MAX_WIDTH_DP,屏幕越宽列数越多
        mBinding.mGridView.setLayoutManager(GridLayoutManager(this, Utils.getAdaptiveGridSpan(Utils.GRID_CARD_MAX_WIDTH_DP)))
        mBinding.mGridView.setAdapter(historyAdapter)

        historyAdapter.onItemLongClickListener = BaseQuickAdapter.OnItemLongClickListener { _, _, position ->
            if (!deleting) {
                if (!historyAdapter.isSelectMode) historyAdapter.enterSelectMode(position)
                else historyAdapter.selectItem(position)
                mBinding.selectActionBar.visibility = View.VISIBLE
                updateDeleteAction()
            }
            true
        }

        historyAdapter.onItemClickListener = BaseQuickAdapter.OnItemClickListener { _, view, position ->
            if (deleting) return@OnItemClickListener
            if (historyAdapter.isSelectMode) {
                historyAdapter.toggleSelection(position)
                updateDeleteAction()
                return@OnItemClickListener
            }
            FastClickCheckUtil.check(view)
            val vodInfo = historyAdapter.data.getOrNull(position) ?: return@OnItemClickListener
            HistoryEntryNavigator.open(this, vodInfo)
        }

        // 清空全部仍保留在标题栏；所选条目的删除只走底部多选栏。
        mBinding.titleBar.setRightDangerIcon(R.drawable.ic_clear, 16f) {
            if (deleting) return@setRightDangerIcon
            ConfirmDialog.showDanger(this, "提示", "确定清空全部观看历史吗？", "清空", {
                deleting = true
                dataEpoch++
                updateDeleteAction()
                showLoadingDialog()
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        HistoryRepositories.history().clear()
                        withContext(Dispatchers.Main) {
                            showHistoryRecords(ArrayList())
                            LogStore.log(Category.SYSTEM, "清空全部观看历史")
                        }
                    } catch (error: Exception) {
                        val remaining = runCatching { loadHistoryRecords() }.getOrNull()
                        withContext(Dispatchers.Main) {
                            if (remaining != null) showHistoryRecords(remaining)
                            AppBubble.toast("清空观看历史失败，请重试")
                        }
                    } finally {
                        withContext(Dispatchers.Main) {
                            deleting = false
                            dismissLoadingDialog()
                            updateDeleteAction()
                        }
                    }
                }
            })
        }

        mBinding.selectActionBar.addAction("全选", SelectActionBar.Kind.NORMAL) { view ->
            if (deleting) return@addAction
            FastClickCheckUtil.check(view)
            historyAdapter.selectAll()
            updateDeleteAction()
        }
        deleteAction = mBinding.selectActionBar.addAction("删除", SelectActionBar.Kind.DANGER) { view ->
            FastClickCheckUtil.check(view)
            confirmDeleteSelection()
        }
        mBinding.selectActionBar.addAction("取消全选", SelectActionBar.Kind.NORMAL) { view ->
            if (deleting) return@addAction
            FastClickCheckUtil.check(view)
            historyAdapter.cancelAllSelection()
            updateDeleteAction()
        }
        updateDeleteAction()
    }

    private fun updateDeleteAction() {
        mBinding.selectActionBar.setActionEnabled(
            deleteAction, !deleting && historyAdapter.selectedCount() > 0
        )
    }

    private fun confirmDeleteSelection() {
        if (deleting) return
        val selectedKeys = historyAdapter.selectedKeySnapshot()
        if (selectedKeys.isEmpty()) return
        ConfirmDialog.showDanger(
            this, "提示", "确定删除所选 ${selectedKeys.size} 条观看历史吗？", "删除", {
                deleting = true
                dataEpoch++
                updateDeleteAction()
                showLoadingDialog()
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val repository = HistoryRepositories.history()
                        selectedKeys.forEach { repository.delete(it.first, it.second) }
                        val remaining = loadHistoryRecords()
                        withContext(Dispatchers.Main) {
                            showHistoryRecords(remaining)
                            LogStore.log(Category.SYSTEM, "删除观看历史 ${selectedKeys.size} 条")
                        }
                    } catch (error: Exception) {
                        // 部分记录可能已删除，失败后重读数据库避免界面仍显示旧条目。
                        val remaining = runCatching { loadHistoryRecords() }.getOrNull()
                        withContext(Dispatchers.Main) {
                            if (remaining != null) showHistoryRecords(remaining)
                            AppBubble.toast("删除观看历史未完成，请重试")
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

    private fun initData() {
        val requestedEpoch = ++dataEpoch
        lifecycleScope.launch(Dispatchers.IO) {
            val records = loadHistoryRecords()
            withContext(Dispatchers.Main) {
                if (requestedEpoch == dataEpoch && !deleting && !historyAdapter.isSelectMode) {
                    showHistoryRecords(records)
                }
            }
        }
    }

    private fun loadHistoryRecords(): ArrayList<VodInfo> {
        // 保留旧订阅来源的记录；来源不可用时卡片仍可进入同名搜索。
        val records = HistoryRepositories.history().query(
            100, null, HistoryHelper.getHisNum(SystemConfig.getHistoryNum())
        )
        return ArrayList<VodInfo>(records.size).apply {
            records.forEach { vodInfo ->
                if (!vodInfo.playNote.isNullOrEmpty()) vodInfo.note = vodInfo.playNote
                add(vodInfo)
            }
        }
    }

    private fun showHistoryRecords(records: ArrayList<VodInfo>) {
        historyAdapter.exitSelectMode()
        historyAdapter.setNewData(records)
        mBinding.selectActionBar.visibility = View.GONE
        updateEmptyState()
        updateDeleteAction()
    }

    /** 历史列表空态:无记录时展示空态占位,否则展示列表 */
    private fun updateEmptyState() {
        val empty = historyAdapter.data.isEmpty()
        mBinding.mGridView.visibility = if (empty) View.GONE else View.VISIBLE
        mBinding.llEmpty.root.visibility = if (empty) View.VISIBLE else View.GONE
        mBinding.topTip.visibility = if (empty) View.GONE else View.VISIBLE
    }

    override fun onBackPressed() {
        if (deleting) return
        if (historyAdapter.isSelectMode) {
            if (historyAdapter.selectedCount() > 0) historyAdapter.cancelAllSelection()
            else {
                historyAdapter.exitSelectMode()
                mBinding.selectActionBar.visibility = View.GONE
            }
            updateDeleteAction()
        } else {
            super.onBackPressed()
        }
    }

    /**
     * 屏幕旋转 / 窗口尺寸变化(大屏横竖屏切换)时,按新宽度重算列数并刷新
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val lm = mBinding.mGridView.layoutManager
        if (lm is GridLayoutManager) {
            lm.spanCount = Utils.getAdaptiveGridSpan(Utils.GRID_CARD_MAX_WIDTH_DP)
        }
    }
}
