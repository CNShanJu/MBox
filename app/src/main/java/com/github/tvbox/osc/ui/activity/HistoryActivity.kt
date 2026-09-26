package com.github.tvbox.osc.ui.activity

import android.content.res.Configuration
import android.os.Bundle
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
import com.github.tvbox.osc.util.Utils
import com.lxj.xpopup.XPopup
import com.owen.tvrecyclerview.widget.V7GridLayoutManager
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
                historyAdapter!!.remove(position)
                com.github.tvbox.osc.repo.HistoryRepositories.history()
                    .delete(vodInfo.sourceKey, vodInfo.id)
                LogStore.log(Category.SYSTEM, "删除历史: " + (vodInfo.name ?: "?"))
                updateEmptyState()
                true
            }

        mBinding.titleBar.setRightIconCustom(R.drawable.ic_clear, 16f, 16f, 12f)
        mBinding.titleBar.rightView.setOnClickListener { view: View? ->
            // 统一主题化确认弹窗(替代 XPopup 默认 asConfirm 库样式)
            com.github.tvbox.osc.ui.dialog.ConfirmDialog.show(this, "提示", "确定清空全部观看历史?", "清空", {
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
                val bundle = Bundle()
                bundle.putString("id", vodInfo.id)
                bundle.putString("sourceKey", vodInfo.sourceKey)
                bundle.putString("vodName", vodInfo.name)
                jumpActivity(DetailActivity::class.java, bundle)
            }
    }

    private fun initData() {

        lifecycleScope.launch(Dispatchers.IO) {
            // 源是否存在/历史保留上限由 UI 层判定(与旧 RoomDataManger 内聚逻辑等价;storage 不再依赖业务配置)
            val allVodRecord = com.github.tvbox.osc.repo.HistoryRepositories.history().query(
                100,
                { key -> com.github.tvbox.osc.spiderapi.SourceConfigProviders.get().getSource(key) != null },
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