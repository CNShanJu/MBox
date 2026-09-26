package com.github.tvbox.osc.ui.activity

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.chad.library.adapter.base.BaseQuickAdapter
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.cache.VodCollect
import com.github.tvbox.osc.databinding.ActivityCollectBinding
import com.github.tvbox.osc.ui.adapter.CollectAdapter
import com.github.tvbox.osc.util.FastClickCheckUtil
import com.github.tvbox.osc.util.Utils
import com.lxj.xpopup.XPopup
import com.owen.tvrecyclerview.widget.V7GridLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CollectActivity : BaseVbActivity<ActivityCollectBinding>() {

    private var collectAdapter  = CollectAdapter()
    override fun init() {
        initView()
        initData()
    }

    private fun initView() {
        // 空态使用显式视图(与历史/订阅/下载页统一),不再依赖 LoadSir
        mBinding.mGridView.setHasFixedSize(true)
        // 列数自适应:单卡宽度不超过 GRID_CARD_MAX_WIDTH_DP,屏幕越宽列数越多
        mBinding.mGridView.setLayoutManager(GridLayoutManager(this, Utils.getAdaptiveGridSpan(Utils.GRID_CARD_MAX_WIDTH_DP)))
        mBinding.mGridView.setAdapter(collectAdapter)
        mBinding.titleBar.setRightIconCustom(R.drawable.ic_clear, 16f, 16f, 12f)
        mBinding.titleBar.rightView.setOnClickListener {
            // 统一主题化确认弹窗(替代 XPopup 默认 asConfirm 库样式)
            com.github.tvbox.osc.ui.dialog.ConfirmDialog.show(this, "提示", "确定清空全部收藏?", "清空", {
                showLoadingDialog()
                lifecycleScope.launch(Dispatchers.IO) {
                    com.github.tvbox.osc.repo.HistoryRepositories.collect().clear()
                    withContext(Dispatchers.Main) {
                        dismissLoadingDialog()
                        collectAdapter.setNewData(ArrayList())
                        mBinding.topTip.visibility = View.GONE
                        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM, "清空全部收藏")
                        updateEmptyState()
                    }
                }
            })
        }
        collectAdapter.onItemLongClickListener =
            BaseQuickAdapter.OnItemLongClickListener { adapter: BaseQuickAdapter<*, *>?, view: View?, position: Int ->
                val vodInfo = collectAdapter.data[position]
                if (vodInfo != null) {
                    val name = vodInfo.name
                    com.github.tvbox.osc.ui.dialog.ConfirmDialog.show(this, "提示", "取消收藏《" + name + "》?", "取消收藏", {
                        collectAdapter.remove(position)
                        com.github.tvbox.osc.repo.HistoryRepositories.collect().deleteById(vodInfo.id)
                        com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM, "取消收藏: " + name)
                        if (collectAdapter.data.isEmpty()) {
                            mBinding.topTip.visibility = View.GONE
                        }
                        updateEmptyState()
                    })
                }
                true
            }
        collectAdapter.onItemClickListener =
            BaseQuickAdapter.OnItemClickListener { adapter, view, position ->
                FastClickCheckUtil.check(view)
                val vodInfo = collectAdapter.data[position]
                if (vodInfo != null) {
                    if (com.github.tvbox.osc.spiderapi.SourceConfigProviders.get().getSource(vodInfo.sourceKey) != null) {
                        val bundle = Bundle()
                        bundle.putString("id", vodInfo.vodId)
                        bundle.putString("sourceKey", vodInfo.sourceKey)
                        bundle.putString("vodName", vodInfo.name)
                        jumpActivity(DetailActivity::class.java, bundle)
                    } else {
//                            Intent newIntent = new Intent(mContext, SearchActivity.class);
                        val newIntent = Intent(mContext, FastSearchActivity::class.java)
                        newIntent.putExtra("title", vodInfo.name)
                        newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        startActivity(newIntent)
                    }
                }
            }
    }

    private fun initData() {
        lifecycleScope.launch(Dispatchers.IO) {
            val allVodRecord = com.github.tvbox.osc.repo.HistoryRepositories.collect().query()
            val vodInfoList: MutableList<VodCollect> = ArrayList()
            for (vodInfo in allVodRecord) {
                vodInfoList.add(vodInfo)
            }
            withContext(Dispatchers.Main) {
                collectAdapter.setNewData(vodInfoList)
                if (vodInfoList.isNotEmpty()) {
                    mBinding.topTip.visibility = View.VISIBLE
                } else {
                    mBinding.topTip.visibility = View.GONE
                }
                updateEmptyState()
            }
        }
    }

    /** 收藏列表空态:无收藏时展示空态占位,否则展示列表 */
    private fun updateEmptyState() {
        val empty = collectAdapter.data.isEmpty()
        mBinding.mGridView.visibility = if (empty) View.GONE else View.VISIBLE
        mBinding.llEmpty.root.visibility = if (empty) View.VISIBLE else View.GONE
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