package com.github.tvbox.osc.base

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.viewbinding.ViewBinding
import com.github.tvbox.osc.R
import com.github.tvbox.osc.callback.EmptyCallback
import com.github.tvbox.osc.callback.LoadingCallback
import com.kingja.loadsir.core.LoadService
import com.kingja.loadsir.core.LoadSir
import me.jessyan.autosize.AutoSize
import me.jessyan.autosize.internal.CustomAdapt
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType

/**
 * Fragment的基类(vb)
 */
abstract class BaseVbFragment<T : ViewBinding> : Fragment(), CustomAdapt {
    @JvmField
    protected var mContext: Context? = null
    @JvmField
    protected var mActivity: Activity? = null

    protected lateinit var mBinding: T
    private var mLoadService: LoadService<*>? = null
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        AutoSize.autoConvertDensity(activity, sizeInDp, isBaseOnWidth)
        // **在真正 inflate 的那一刻装注入器**(比 onGetLayoutInflater 更可靠:
        // Fragment 会缓存/复用 inflater 实例,那个回调不一定被走到 —— 真机上
        // "我的"页的悬浮球就是这么漏掉的:注入器装了,但造那个布局的不是这份 inflater)。
        try {
            com.github.tvbox.osc.theme.ThemeInflaterFactory.install(inflater)
        } catch (ignored: Throwable) {
        }
        return initBindingViewRoot(inflater, container)
    }

    /**
     * **Fragment 自己的 inflater 也要装主题注入器**(2026-10-01 真机定位到的根因)。
     *
     * `Fragment.getLayoutInflater()` 拿到的是 Activity inflater 的**克隆**
     * (`LayoutInflater.cloneInContext`),而注入器是反射写进 `mFactory2` 的私有字段 ——
     * 克隆不一定带过去。于是"卡片底/搜索框底不跟主题走"这类问题在看板上表现为:
     * 同一个 Activity 里 `ThemeSweep` 兜底的那些 id 变了色,而布局属性注入的那批没变
     * (真机实测:底部导航栏拿到主题蓝 #B84949FF,搜索框仍是编译期灰 #B8ECECF4)。
     *
     * 这里在"取 inflater"这一刻补装一次(幂等),让本模块所有 Fragment 的布局都走注入通道;
     * 比逐个 Fragment 在 `onViewCreated` 里补色(ThemeSweep)更彻底 —— 后者只能按登记 id 猜。
     */
    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
        val inflater = super.onGetLayoutInflater(savedInstanceState)
        try {
            com.github.tvbox.osc.theme.ThemeInflaterFactory.install(inflater)
        } catch (ignored: Throwable) {
        }
        return inflater
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 换肤兜底:Fragment 的布局由 Framework/AutoSize 那条路 inflate,注入器抓不全,
        // 这里按"背景色还是不是内置那份"再补一遍(见 ThemeSweep 的说明)
        try {
            com.github.tvbox.osc.theme.ThemeSweep.apply(view)
        } catch (ignored: Throwable) {
        }
        init()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        mContext = context
        mActivity = context as Activity
    }

    /**
     * 初始化viewBinding返回根布局
     *
     * @param inflater **必须用 onCreateView 传进来的那份**(而不是再调 `getLayoutInflater()`):
     *   后者可能返回另一份实例,注入器装在前者、布局由后者造 —— 主题就白装了。
     */
    private fun initBindingViewRoot(inflater: LayoutInflater, container: ViewGroup?): View? {
        val type = javaClass.genericSuperclass as ParameterizedType
        val aClass = type.actualTypeArguments[0] as Class<*>
        val method: Method
        try {
            method = aClass.getDeclaredMethod(
                "inflate",
                LayoutInflater::class.java,
                ViewGroup::class.java,
                Boolean::class.javaPrimitiveType
            )
            mBinding = method.invoke(null, inflater, container, false) as T
            return mBinding.root
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    /**
     * 在滑动或者跳转的过程中，第一次创建fragment的时候均会调用onResume方法
     */
    override fun onResume() {
        AutoSize.autoConvertDensity(activity, sizeInDp, isBaseOnWidth)
        super.onResume()
    }

    protected abstract fun init()
    protected fun setLoadSir(view: View?) {
        if (mLoadService == null) {
            mLoadService = LoadSir.getDefault().register(view) { }
        }
    }

    protected fun setLoadSir2(view: View?) {
        mLoadService = LoadSir.getDefault().register(view) { }
    }

    protected fun showLoading() {
        if (mLoadService != null) {
            mLoadService!!.showCallback(LoadingCallback::class.java)
        }
    }

    protected fun showEmpty() {
        showEmpty(null)
    }

    /**
     * 空态 + 自定义说明(如"该源插件不可用:缺少 X 类"/"数据源无响应,超时")。
     * <p>
     * 口径与 {@code BaseActivity#showEmpty(String)} 一致:LoadSir 的 EmptyCallback 视图是全 App 复用的
     * 同一份(view_empty.xml),所以<b>每次都要把文案设回去</b>(tip 为空即恢复默认「暂无数据」),
     * 否则上一页留下的定制文案会跟着跑到别的页面。
     */
    protected fun showEmpty(tip: String?) {
        if (null != mLoadService) {
            mLoadService!!.showCallback(EmptyCallback::class.java)
            applyEmptyTip(tip)
        }
    }

    /** 把原因写进空态那份共享布局;找不到该 TextView(布局换过)时静默降级为纯空态 */
    private fun applyEmptyTip(tip: String?) {
        try {
            val tv = mLoadService?.loadLayout?.findViewById<TextView>(R.id.tv_empty_text)
            tv?.text = if (tip.isNullOrEmpty()) getString(R.string.empty_default_tip) else tip
        } catch (ignored: Throwable) {
        }
    }

    protected fun showSuccess() {
        if (null != mLoadService) {
            mLoadService!!.showSuccess()
        }
    }

    fun jumpActivity(clazz: Class<out BaseActivity?>?) {
        val intent = Intent(mContext, clazz)
        startActivity(intent)
    }

    fun jumpActivity(clazz: Class<out BaseActivity?>?, bundle: Bundle?) {
        val intent = Intent(mContext, clazz)
        intent.putExtras(bundle!!)
        startActivity(intent)
    }

    override fun getSizeInDp(): Float {
        return if (activity != null && activity is CustomAdapt) (activity as CustomAdapt?)!!.sizeInDp else 0f
    }

    override fun isBaseOnWidth(): Boolean {
        return if (activity != null && activity is CustomAdapt) (activity as CustomAdapt?)!!.isBaseOnWidth else true
    }
}
