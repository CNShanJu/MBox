package com.github.tvbox.osc.ui.activity

import android.os.Build
import android.os.Process
import android.view.MenuItem
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentPagerAdapter
import androidx.viewpager.widget.ViewPager.SimpleOnPageChangeListener
import com.blankj.utilcode.util.ActivityUtils
import com.github.tvbox.osc.R
import com.github.tvbox.osc.util.AppBubble
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.constant.IntentKey
import com.github.tvbox.osc.config.SystemConfig
import com.github.tvbox.osc.databinding.ActivityMainBinding
import com.github.tvbox.osc.ui.fragment.GridFragment
import com.github.tvbox.osc.ui.fragment.HomeFragment
import com.github.tvbox.osc.ui.fragment.MyFragment
import kotlin.system.exitProcess

class MainActivity : BaseVbActivity<ActivityMainBinding>() {

    var fragments = listOf(HomeFragment(), MyFragment())
    var useCacheConfig = false
    private var exitTime = 0L

    /** 首页展示联网内容；“我的”页可能正在查看本地内容，不主动弹无网页。 */
    fun isOnlineContentVisible(): Boolean = mBinding.vp.currentItem == 0

    override fun init() {

        // 主题真重启会清空 Intent 标志；只在下一进程的一次主页创建中复用磁盘配置缓存。
        if (SystemConfig.consumeThemeRestartUseCache()) {
            intent.putExtra(IntentKey.CACHE_CONFIG_CHANGED, true)
        }
        useCacheConfig = intent.getBooleanExtra(IntentKey.CACHE_CONFIG_CHANGED, false)

        mBinding.vp.adapter = object : FragmentPagerAdapter(supportFragmentManager) {
            override fun getItem(position: Int): Fragment {
                return fragments[position]
            }

            override fun getCount(): Int {
                return fragments.size
            }
        }

        mBinding.bottomNav.setOnNavigationItemSelectedListener { menuItem: MenuItem ->
            mBinding.vp.setCurrentItem(menuItem.order, false)
            updateNavIcons(menuItem.order)
            true
        }
        // 底栏条目长按:不再弹系统 Tooltip 提示,改为在条目上方放一簇烟花 ——
        // 接管点在条目视图自身(见 ui/kit/FireworksBottomNavigationView),因为 Material 每次
        // 初始化条目都会把 Tooltip 重新挂上,只在这里清一次会被后续菜单更新覆盖。
        mBinding.vp.addOnPageChangeListener(object : SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) {
                mBinding.bottomNav.menu.getItem(position).setChecked(true)
                updateNavIcons(position)
            }
        })
        updateNavIcons(0)
    }

    /** 底部导航图标: 选中项换"选中"变体(与未选中图形区分), 颜色仍由 itemIconTint 按状态着色 */
    private fun updateNavIcons(position: Int) {
        val menu = mBinding.bottomNav.menu
        if (menu.size() >= 2) {
            menu.getItem(0).setIcon(
                if (position == 0) R.drawable.ic_nav_home_sel else R.drawable.ic_nav_home
            )
            menu.getItem(1).setIcon(
                if (position == 1) R.drawable.ic_nav_my_sel else R.drawable.ic_nav_my
            )
        }
    }

    override fun onBackPressed() {
        if (mBinding.vp.currentItem != 0) { // 非首页(我的)按返回回首页
            mBinding.vp.currentItem = 0
            return
        }
        val homeFragment = fragments[0] as HomeFragment
        if (!homeFragment.isAdded) { // 资源不足销毁重建时未挂载到activity时getChildFragmentManager会崩溃
            confirmExit()
            return
        }
        val childFragments = homeFragment.allFragments
        if (childFragments.isEmpty()) { //加载中(没有tab)
            confirmExit()
            return
        }
        val fragment: Fragment = childFragments[homeFragment.tabIndex]
        if (fragment is GridFragment) { // 首页数据源动态加载的tab
            if (!fragment.restoreView()) { // 有回退的view,先回退(AList等文件夹列表),没有可回退的,返到主页tab
                if (!homeFragment.scrollToFirstTab()) {
                    confirmExit()
                }
            }
        } else {
            confirmExit()
        }
    }

    private fun confirmExit() {
        if (System.currentTimeMillis() - exitTime > 2000) {
            AppBubble.toast("再按一次退出程序")
            exitTime = System.currentTimeMillis()
        } else {
            ActivityUtils.finishAllActivities(true)
            Process.killProcess(Process.myPid())
            exitProcess(0)
        }
    }
}
