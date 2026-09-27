package com.github.tvbox.osc.ui.activity

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.blankj.utilcode.util.FileUtils
import com.blankj.utilcode.util.GsonUtils
import com.blankj.utilcode.util.SPUtils
import com.chad.library.adapter.base.BaseQuickAdapter
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.bean.VideoInfo
import com.github.tvbox.osc.constant.CacheConst
import com.github.tvbox.osc.databinding.ActivityMovieFoldersBinding
import com.github.tvbox.osc.ui.adapter.LocalVideoAdapter
import com.github.tvbox.osc.ui.kit.SelectActionBar
import com.github.tvbox.osc.util.FastClickCheckUtil
import com.github.tvbox.osc.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.stream.Collectors

class VideoListActivity : BaseVbActivity<ActivityMovieFoldersBinding>() {
    private var mBucketDisplayName = ""
    private var mLocalVideoAdapter = LocalVideoAdapter()
    private var mSelectedCount = 0

    /** 操作栏上的"删除"键:可用态由选中数决定(取色走组件) */
    private lateinit var mDeleteAction: TextView

    override fun init() {
        mBucketDisplayName = intent.extras?.getString("bucketDisplayName")?:""

        mBinding.titleBar.setTitle(mBucketDisplayName)
        mBinding.rv.setAdapter(mLocalVideoAdapter)
        mLocalVideoAdapter.onItemClickListener =
            BaseQuickAdapter.OnItemClickListener { adapter: BaseQuickAdapter<*, *>, view: View?, position: Int ->
                val videoInfo = adapter.getItem(position) as VideoInfo?
                if (mLocalVideoAdapter.isSelectMode) {
                    // 走适配器勾选入口:计数增量维护 + 只刷新该行
                    mLocalVideoAdapter.setItemChecked(videoInfo!!, !videoInfo.isChecked)
                } else {
                    val bundle = Bundle()
                    //                    bundle.putString("path",videoInfo.getPath());
                    bundle.putString("videoList", GsonUtils.toJson(mLocalVideoAdapter.data))
                    bundle.putInt("position", position)
                    jumpActivity(LocalPlayActivity::class.java, bundle)
                }
            }
        mLocalVideoAdapter.onItemLongClickListener =
            BaseQuickAdapter.OnItemLongClickListener { adapter: BaseQuickAdapter<*, *>, view: View?, position: Int ->
                toggleListSelectMode(true)
                val videoInfo = adapter.getItem(position) as VideoInfo?
                mLocalVideoAdapter.setItemChecked(videoInfo!!, true)
                true
            }

        // 长按多选操作栏:公共组件 ui/kit/SelectActionBar(与下载页同一款:主题面背景 + 危险键色),
        // 页面只负责"加哪几个键、点了干什么",颜色/可用态由组件按主题与 Kind 决定
        mBinding.selectActionBar.addAction("全选", SelectActionBar.Kind.NORMAL) { view: View? ->
            FastClickCheckUtil.check(view)
            mLocalVideoAdapter.selectAll()
        }
        mDeleteAction = mBinding.selectActionBar.addAction("删除", SelectActionBar.Kind.DANGER) { view: View? ->
            FastClickCheckUtil.check(view)
            // 统一主题化确认弹窗(替代 XPopup 默认 asConfirm);删除不可逆 → 确认键走危险色
            com.github.tvbox.osc.ui.dialog.ConfirmDialog.showDanger(this, "提示", "确定删除所选视频吗？", "删除", {
                showLoadingDialog()
                lifecycleScope.launch(Dispatchers.IO) {
                    val data = mLocalVideoAdapter.data
                    val deleteList: MutableList<VideoInfo> = ArrayList()
                    for (item in data) {
                        if (item.isChecked) {
                            deleteList.add(item)
                            if (FileUtils.delete(item.path)) {
                                // 删除缓存的影片时长、进度
                                SPUtils.getInstance(CacheConst.VIDEO_DURATION_SP).remove(item.path)
                                SPUtils.getInstance(CacheConst.VIDEO_PROGRESS_SP).remove(item.path)
                                // 联动清理已下载档案 + 下载任务: 否则详情页仍显示"已下载",
                                // 且残留任务会在网络恢复时自动续传把已删文件又下回来
                                // UI 只通过门面操作档案/任务(改进.txt 第一阶段边界)
                                com.github.tvbox.osc.download.DownloadFacade.get().removeArchiveByPath(item.path)
                                com.github.tvbox.osc.download.DownloadFacade.get().removeTasksByPath(item.path)
                                // 文件增删需要通知系统扫描,否则删除文件后还能查出来
                                // 这个工具类直接传文件路径不知道为啥通知失败,手动获取一下
                                FileUtils.notifySystemToScan(FileUtils.getDirName(item.path))
                            }
                        }
                    }
                    data.removeAll(deleteList)

                    withContext(Dispatchers.Main) {
                        dismissLoadingDialog()
                        mLocalVideoAdapter.notifyDataSetChanged()
                        toggleListSelectMode(false)
                    }
                }
            })
        }
        mBinding.selectActionBar.addAction("取消全选", SelectActionBar.Kind.NORMAL) { view: View? ->
            FastClickCheckUtil.check(view)
            cancelAll()
        }
        // 没有选中项时"删除"置灰(取色由组件按 Kind 决定:text_danger / text_disable)
        mBinding.selectActionBar.setActionEnabled(mDeleteAction, false)

        mLocalVideoAdapter.setOnSelectCountListener { count: Int ->
            mSelectedCount = count
            mBinding.selectActionBar.setActionEnabled(mDeleteAction, mSelectedCount > 0)
        }
    }

    private fun toggleListSelectMode(open: Boolean) {
        mLocalVideoAdapter.setSelectMode(open)
        mBinding.selectActionBar.visibility = if (open) View.VISIBLE else View.GONE
        if (!open) { // 开启时设置了当前item为选中状态已经刷新了.所以只在关闭刷新列表
            mLocalVideoAdapter.notifyDataSetChanged()
        }
    }

    private fun cancelAll() {
        mLocalVideoAdapter.cancelAllSelection()
    }

    override fun onResume() {
        super.onResume()
        groupVideos()
    }

    /**
     * 根据文件夹名字筛选视频
     */
    private fun groupVideos() {
        val videoList = Utils.getVideoList()
        val collect = videoList.stream()
            .filter { videoInfo: VideoInfo -> videoInfo.bucketDisplayName == mBucketDisplayName }
            .collect(Collectors.toList())
        mLocalVideoAdapter.setNewData(collect)
        // 新数据就位后同步一次选中计数(列表结构变化后 BRVAH 的 notifyDataSetChanged 为 final,无法拦截)
        mLocalVideoAdapter.syncSelection()
    }

    override fun onBackPressed() {
        if (mLocalVideoAdapter.isSelectMode) {
            if (mSelectedCount > 0) {
                cancelAll()
            } else {
                toggleListSelectMode(false)
            }
        } else {
            super.onBackPressed()
        }
    }
}