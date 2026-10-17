package com.github.tvbox.osc.ui.activity

import android.view.View
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.databinding.ActivityPlaybackCacheBinding
import com.github.tvbox.osc.player.api.PlayConfig
import com.github.tvbox.osc.util.FastClickCheckUtil
import com.google.android.material.slider.Slider
import java.util.Locale

/**
 * 播放缓存设置(二级页):仅在设置页"开启播放缓存"打开时进入。
 * 四项:下一集预载(开关,Exo 直链源)、预载时长(滑杆,下一集自片头后预载的秒数)、
 * 边播边缓存(开关,当前播放写磁盘缓存)、缓存容量(滑杆,磁盘缓存上限)。
 * 滑杆拖动过程只刷新右侧数值,松手才写配置,避免每帧落盘。
 */
class PlaybackCacheActivity : BaseVbActivity<ActivityPlaybackCacheBinding>() {

    override fun init() {
        mBinding.titleBar.setOnBackClickListener { onBackPressed() }
        initSwitches()
        initPreloadDuration()
        initCacheCapacity()
        updateRowVisibility()
    }

    /** 预载时长跟随"下一集预载"、缓存容量跟随"边播边缓存":关了对应开关就收起设置行 */
    private fun updateRowVisibility() {
        mBinding.llPreloadDuration.visibility =
            if (PlayConfig.isPreloadNextEnabled()) View.VISIBLE else View.GONE
        mBinding.llCacheCapacity.visibility =
            if (PlayConfig.isCacheWhilePlay()) View.VISIBLE else View.GONE
    }

    private fun initSwitches() {
        mBinding.switchPreloadNext.setChecked(PlayConfig.isPreloadNextEnabled())
        mBinding.llPreloadNext.setOnClickListener { v ->
            FastClickCheckUtil.check(v)
            val on = !PlayConfig.isPreloadNextEnabled()
            mBinding.switchPreloadNext.setChecked(on)
            PlayConfig.setPreloadNextEnabled(on)
            updateRowVisibility()
        }
        mBinding.switchCacheWhilePlay.setChecked(PlayConfig.isCacheWhilePlay())
        mBinding.llCacheWhilePlay.setOnClickListener { v ->
            FastClickCheckUtil.check(v)
            val on = !PlayConfig.isCacheWhilePlay()
            mBinding.switchCacheWhilePlay.setChecked(on)
            PlayConfig.setCacheWhilePlay(on)
            updateRowVisibility()
        }
    }

    private fun initPreloadDuration() {
        mBinding.sliderPreloadDuration.apply {
            valueFrom = PlayConfig.PRELOAD_DURATION_MIN_S.toFloat()
            valueTo = PlayConfig.PRELOAD_DURATION_MAX_S.toFloat()
            stepSize = 1f
            value = PlayConfig.getPreloadDurationS().toFloat()
            addOnChangeListener { _, value, _ ->
                mBinding.tvPreloadDuration.text = formatSeconds(value.toInt())
            }
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) = Unit
                override fun onStopTrackingTouch(slider: Slider) {
                    PlayConfig.setPreloadDurationS(slider.value.toInt())
                }
            })
        }
        mBinding.tvPreloadDuration.text = formatSeconds(PlayConfig.getPreloadDurationS())
    }

    private fun initCacheCapacity() {
        mBinding.sliderCacheCapacity.apply {
            valueFrom = PlayConfig.CACHE_CAPACITY_MIN_MB.toFloat()
            valueTo = PlayConfig.CACHE_CAPACITY_MAX_MB.toFloat()
            stepSize = 1f
            value = PlayConfig.getCacheCapacityMb().toFloat()
            addOnChangeListener { _, value, _ ->
                mBinding.tvCacheCapacity.text = formatCapacityMb(value.toInt())
            }
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) = Unit
                override fun onStopTrackingTouch(slider: Slider) {
                    PlayConfig.setCacheCapacityMb(slider.value.toInt())
                }
            })
        }
        mBinding.tvCacheCapacity.text = formatCapacityMb(PlayConfig.getCacheCapacityMb())
    }

    private fun formatSeconds(s: Int) = "${s}s"

    private fun formatCapacityMb(mb: Int): String =
        if (mb >= 1024) String.format(Locale.US, "%.1fGB", mb / 1024.0) else "${mb}MB"
}
