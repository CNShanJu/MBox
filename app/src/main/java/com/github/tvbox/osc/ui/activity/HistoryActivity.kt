package com.github.tvbox.osc.ui.activity

import android.content.res.Configuration
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.chad.library.adapter.base.BaseQuickAdapter
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.databinding.ActivityHistoryBinding
import com.github.tvbox.osc.log.Category
import com.github.tvbox.osc.log.LogStore
import com.github.tvbox.osc.ui.adapter.HistoryAdapter
import com.github.tvbox.osc.util.FastClickCheckUtil
import com.github.tvbox.osc.util.HistoryEntryNavigator
import com.github.tvbox.osc.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HistoryActivity : BaseVbActivity<ActivityHistoryBinding>() {
    private var historyAdapter: HistoryAdapter? = null
    override fun init() {
        initView()
        initData()
    }

    private fun initView() {
        // 空态使用显式视图(与订阅/下载页统一),不再依赖 LoadSir 注册
        mBinding.mGridView.setHasFixedSize(true)
        // 列数自适应:单卡宽度不超过 GRID_CARD_MAX_WIDTH_DP,屏幕越宽列数越多
        mBinding.mGridView.setLayoutManager(GridLayoutManager(this, Utils.getAdaptiveGridSpan(Utils.GRID_CARD_MAX_WIDTH_DP)))
        historyAdapter = HistoryAdapter()
        mBinding.mGridView.setAdapter(historyAdapter)

        historyAdapter!!.onItemLongClickListener =
            BaseQuickAdapter.OnItemLongClickListener { _: BaseQuickAdapter<*, *>?, view: View?, position: Int ->
                FastClickCheckUtil.check(view)
                val vodInfo = historyAdapter!!.data[position]
                com.github.tvbox.osc.ui.dialog.ConfirmDialog.showDanger(
                    this, "删除观看历史", "确定删除《${vodInfo.name ?: "该影片"}》的观看记录吗？", "删除"
                ) {
                    val currentPosition = historyAdapter!!.data.indexOf(vodInfo)
                    if (currentPosition < 0) return@showDanger
                    historyAdapter!!.remove(currentPosition)
                    com.github.tvbox.osc.repo.HistoryRepositories.history()
                        .delete(vodInfo.sourceKey, vodInfo.id)
                    LogStore.log(Category.SYSTEM, "删除历史: " + (vodInfo.name ?: "?"))
                    updateEmptyState()
                }
                true
            }

        // 标题栏右侧"清空"图标:沿用 AppTitleBar 触区，图标使用危险色。
        mBinding.titleBar.setRightDangerIcon(R.drawable.ic_clear, 16f) {
            // 统一主题化确认弹窗(替代 XPopup 默认 asConfirm 库样式)
            com.github.tvbox.osc.ui.dialog.ConfirmDialog.showDanger(this, "提示", "确定清空全部观看历史?", "清空", {
                showLoadingDialog()
                lifecycleScope.launch(Dispatchers.IO) {
                    com.github.tvbox.osc.repo.HistoryRepositories.history().clear()
                    // 在主线程更新数据
                    withContext(Dispatchers.Main) {
                        dismissLoadingDialog()
                        historyAdapter!!.setNewData(ArrayList())
                        mBinding.topTip.visibility = View.GONE
                        LogStore.log(Category.SYSTEM, "清空全部观看历史")
                        updateEmptyState()
                    }
                }
            })
        }

        historyAdapter!!.onItemClickListener =
            BaseQuickAdapter.OnItemClickListener { _: BaseQuickAdapter<*, *>?, view: View?, position: Int ->
                FastClickCheckUtil.check(view)
                val vodInfo = historyAdapter!!.data[position]
                HistoryEntryNavigator.open(this, vodInfo)
            }
    }

    private fun initData() {

        lifecycleScope.launch(Dispatchers.IO) {
            // 保留旧订阅来源的记录；来源不可用时卡片仍可进入同名搜索。
            val allVodRecord = com.github.tvbox.osc.repo.HistoryRepositories.history().query(
                100,
                null,
                com.github.tvbox.osc.util.HistoryHelper.getHisNum(
                    com.github.tvbox.osc.config.SystemConfig.getHistoryNum()
                )
            )
            val vodInfoList: MutableList<VodInfo> = ArrayList()
            for (vodInfo in allVodRecord) {
                if (vodInfo.playNote != null && vodInfo.playNote.isNotEmpty()) vodInfo.note =
                    vodInfo.playNote
                vodInfoList.add(vodInfo)
            }

            withContext(Dispatchers.Main) {
                historyAdapter!!.setNewData(vodInfoList)
                if (vodInfoList.isNotEmpty()) {
                    mBinding.topTip.visibility = View.VISIBLE
                } else {
                    mBinding.topTip.visibility = View.GONE
                }
                updateEmptyState()
            }
        }
    }

    /** 历史列表空态:无记录时展示空态占位,否则展示列表 */
    private fun updateEmptyState() {
        val empty = historyAdapter!!.data.isEmpty()
        mBinding.mGridView.visibility = if (empty) View.GONE else View.VISIBLE
        mBinding.llEmpty.root.visibility = if (empty) View.VISIBLE else View.GONE    }

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
