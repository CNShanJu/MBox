package com.github.tvbox.osc.base;

import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.PermissionChecker;

import com.blankj.utilcode.util.ActivityUtils;
import com.blankj.utilcode.util.AppUtils;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.callback.EmptyCallback;
import com.github.tvbox.osc.callback.LoadingCallback;
import com.github.tvbox.osc.player.api.PlayConfig;
import com.github.tvbox.osc.ui.activity.DetailActivity;
import com.github.tvbox.osc.util.AppManager;
import com.github.tvbox.osc.util.Utils;
import com.gyf.immersionbar.ImmersionBar;
import com.kingja.loadsir.callback.Callback;
import com.kingja.loadsir.core.LoadService;
import com.kingja.loadsir.core.LoadSir;
import com.lxj.xpopup.core.BasePopupView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import me.jessyan.autosize.internal.CustomAdapt;

public abstract class BaseActivity extends AppCompatActivity implements CustomAdapt {
    protected Context mContext;
    private LoadService mLoadService;

    private ImmersionBar mImmersionBar;
    private com.github.tvbox.osc.ui.kit.AppTitleBar mTitleBar;
    private BasePopupView loadingPopup;
    /** 换肤后的 Resources(见下面的 getResources 覆写);没在用自定义主题时为 null */
    private android.content.res.Resources mThemedResources;

    /**
     * 运行时换肤第一层:把 Resources 换成主题感知的那份(见 theme/ThemeContextWrapper)。
     * <p>没用自定义主题时<b>原样返回</b>,等于这条链路不存在;
     * 用了自定义主题时,代码里 {@code ContextCompat.getColor(...)} 这类取色会自动跟着走。
     */
    @Override
    protected void attachBaseContext(Context newBase) {
        // 换肤快照先对齐再生效:「跟随系统」下手机翻明暗不重启进程,生效主题(该类型的默认主题)却已经换了;
        // 必须在 wrap 之前刷新,否则这次创建的 Activity 会整轮沿用旧快照(该介入时没介入)。
        // refresh() 只在解析结果真变了才换快照 + 清派生缓存,没变就一次比较而已
        try {
            com.github.tvbox.osc.theme.ThemeRuntime.refresh();
        } catch (Throwable ignored) {
        }
        super.attachBaseContext(com.github.tvbox.osc.theme.ThemeContextWrapper.wrap(newBase));
    }

    /**
     * 换肤的"代码取色"通道:**必须覆写**,只在 attachBaseContext 里包一层不够。
     *
     * <p>AppCompat 会给 Activity 下 {@code applyOverrideConfiguration}(夜间模式等),
     * 此后 {@code ContextThemeWrapper.getResourcesInternal()} 走
     * {@code createConfigurationContext(...)} **自己新建一份 Resources** —— 那份不是换肤用的包装,
     * 于是代码里的 {@code getColor} / {@code getDrawable} / {@code getColorStateList} 全部绕过主题:
     * 标题栏文字(代码里取 {@code R.color.text_main})、列表项颜色、没显式写 {@code app:tint} 的矢量图标
     * 统统停在内置配色;而布局里行内写的颜色走 inflater 注入又是对的 ——
     * 用户看到的"有的变了、有的没变"正是这一条(见 ThemeContextWrapper#wrapResources)。
     *
     * <p>这里在出口兜一层(幂等、带缓存):没在用自定义主题时原样返回,等于这条链路不存在。
     */
    @Override
    public android.content.res.Resources getResources() {
        android.content.res.Resources base = super.getResources();
        if (base == null) return null;
        android.content.res.Resources themed = mThemedResources;
        if (themed instanceof com.github.tvbox.osc.theme.ThemeResources) {
            // 配置可能变了(字号/屏幕/日夜),对齐一次再交出去
            ((com.github.tvbox.osc.theme.ThemeResources) themed).syncFrom(base);
            return themed;
        }
        mThemedResources = com.github.tvbox.osc.theme.ThemeContextWrapper.wrapResources(base);
        return mThemedResources;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mContext = this;
        AppManager.getInstance().addActivity(this);
        // 系统从已回收的任务栈直接恢复页面时，先转到统一开屏入口。
        // 放在布局 inflate 之前，避免把旧页面和首页组件先创建一遍再重启。
        if (!App.getInstance().isNormalStart && allowDirectColdStart()) {
            App.getInstance().isNormalStart = true;
        } else if (!App.getInstance().isNormalStart) {
            try {
                if (getPackageManager().getLaunchIntentForPackage(getPackageName()) != null) {
                    com.github.tvbox.osc.config.SystemConfig.markInternalRestart();
                    AppUtils.relaunchApp(true);
                    // relaunchApp 正常结束当前进程；若它未能发起，继续显示当前页面。
                    com.github.tvbox.osc.config.SystemConfig.clearInternalRestart();
                }
            } catch (Throwable failure) {
                com.github.tvbox.osc.config.SystemConfig.clearInternalRestart();
                android.util.Log.w("MBox-Startup", "系统恢复时重启应用失败", failure);
            }
            App.getInstance().isNormalStart = true;
        }

        // 「跟随系统」翻明暗的比对基准:记下建这页时系统的明暗(见 handleSystemNightChange)
        createdSystemNight = systemNightNow();

        // 运行时换肤第二层:窗口底色(bg_body)与布局属性注入。
        // **必须在内容布局 inflate 之前**:Activity 自己的布局(onCreate 里的 setContentView / ViewBinding
        // inflate)也是靠这个注入器改色的,装晚了这一层整个不参与换肤 —— 页面看着"和内置主题一模一样",
        // 用户口径就是"我自定义的主题没效果"。放在 super.onCreate 之后是因为 AppCompat 到那时才把
        // 自己的 LayoutInflater.Factory2 装好,本类要链在它后面(先让它造 AppCompat 控件,再由本类改颜色)。
        try {
            com.github.tvbox.osc.theme.ThemeRuntime.applyTo(this);
            com.github.tvbox.osc.theme.ThemeRuntime.installInflaterFactory(this);
        } catch (Throwable ignored) {
        }

        if (getLayoutResID()==-1){
            initVb();
        }else {
            setContentView(getLayoutResID());
        }
        // 临时兼容层只处理 ThemeSweep 明确登记的 View id/令牌；禁止按现有像素颜色猜语义。
        try {
            View content = getWindow() == null ? null : getWindow().getDecorView();
            com.github.tvbox.osc.theme.ThemeSweep.apply(content);
        } catch (Throwable ignored) {
        }
        // 全局页面背景层("body"底图):挂到内容容器最底层,所有页面透明处即显示背景图
        // 图源/遮罩/缩放位置统一走系统配置门面(SystemConfig)组装,设置页改完各页 onResume 自动套用
        try {
            if (shouldAttachPageBackground()) attachPageBackground();
        } catch (Throwable ignored) {
        }
        initStatusBar();
        init();
        // 再补一次:页面在 init() 里动态加出来的视图(主题编辑页那样一行行现建的卡片)
        // 不在上面那次扫描的树里(用户口径:"编辑主题里还存在部分卡片背景色没走")
        try {
            com.github.tvbox.osc.theme.ThemeSweep.apply(getWindow() == null ? null : getWindow().getDecorView());
        } catch (Throwable ignored) {
        }
        // 暂停页面创建时的主题诊断与按需全树采集。
        /* 临时停用页面主题探针，保留代码供后续排障。
        try {
            final View probeRoot = getWindow() == null ? null : getWindow().getDecorView();
            if (probeRoot != null) {
                probeRoot.post(() -> com.github.tvbox.osc.theme.RadiusCheck.reportViews(
                        probeRoot, getClass().getSimpleName(),
                        new int[]{R.id.search, R.id.tvName, R.id.bottom_nav_surface,
                                R.id.my_surface_card, R.id.btn_live, R.id.btn_filter,
                                R.id.update_bubble, R.id.ivThumb, R.id.tvYear}));
                // 按需全树体检:`adb shell am start -n <pkg>/<Activity> --ez dump_theme true`
                // 会在 3 秒后把"所有带底的控件 + 真色"打进 logcat —— 换自定义主题后想在某个页面上
                // 一次性看清"哪些控件没跟上",用它(平时不带这个参数,零开销)。
                if (getIntent() != null && getIntent().getBooleanExtra("dump_theme", false)) {
                    probeRoot.postDelayed(() ->
                            com.github.tvbox.osc.theme.RadiusCheck.dumpAllBackgrounds(probeRoot, getClass().getSimpleName()), 3000);
                }
            }
        } catch (Throwable ignored) {
        }
        */
    }

    /** 启动器开屏或通知直达页面可在冷进程里直接创建。 */
    protected boolean allowDirectColdStart() {
        return false;
    }


    private void initStatusBar(){
        ImmersionBar.with(this)
                .statusBarDarkFont(!Utils.isDarkTheme())
                .titleBar(findTitleBar(getWindow().getDecorView().findViewById(android.R.id.content)))
                .navigationBarColor(android.R.color.transparent)
                .init();
    }

    /**
     * 递归获取 ViewGroup 中的统一头部(AppTitleBar)。
     *
     * <p>头部(标题 + 返回 + 右侧动作)现在是一个**自包含组件**(ui/kit/AppTitleBar):返回键、标题几何、
     * 右侧动作的间距都在组件内部定义,不再靠 {@code OnTitleBarListener} 回调接线 ——
     * 以前用 hjq TitleBar 时它的左图标是 compound drawable,垂直位置按"文本行盒"算,永远跟标题对不齐。
     * 需要"返回=确认"这类语义的页面自己调 {@code AppTitleBar#setOnBackClickListener}。
     */
    private com.github.tvbox.osc.ui.kit.AppTitleBar findTitleBar(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View view = group.getChildAt(i);
            if (view instanceof com.github.tvbox.osc.ui.kit.AppTitleBar) {
                return (com.github.tvbox.osc.ui.kit.AppTitleBar) view;
            } else if (view instanceof ViewGroup) {
                com.github.tvbox.osc.ui.kit.AppTitleBar titleBar = findTitleBar((ViewGroup) view);
                if (titleBar != null) {
                    return titleBar;
                }
            }
        }
        return null;
    }

    private com.github.tvbox.osc.ui.kit.AppTitleBar getTitleBar() {
        if (mTitleBar == null) {
            mTitleBar = findTitleBar(getWindow().getDecorView().findViewById(android.R.id.content));
        }
        return mTitleBar;
    }


    /**
     * 挂载/刷新全局页面背景层("body"底图):图源、遮罩不透明度、缩放与位置都从
     * {@link com.github.tvbox.osc.config.SystemConfig} 门面组装(见 util/PageBackgroundStore),
     * 设置页改完配置后,各页面 onResume 走这里自动套用。
     * <p>
     * 顺带接上"老配置位移→锚点"的迁移回调:背景层拿到图片尺寸后换算出的锚点在这里落盘(一次性),
     * 之后换横竖屏/分辨率都按锚点还原,不会出现"竖屏摆好的图转横屏自己往中间跑"。
     */
    protected boolean shouldAttachPageBackground() {
        return true;
    }

    protected final void attachPageBackground() {
        com.github.tvbox.osc.ui.kit.PageBackgroundView layer =
                com.github.tvbox.osc.ui.kit.PageBackgroundView.attach(this,
                        com.github.tvbox.osc.util.PageBackgroundStore.currentConfig());
        if (layer != null) {
            layer.setOnLegacyMigratedListener(com.github.tvbox.osc.util.PageBackgroundStore::persistAnchors);
        }
    }

    public boolean hasPermission(String permission) {
        boolean has = true;
        try {
            has = PermissionChecker.checkSelfPermission(this, permission) == PermissionChecker.PERMISSION_GRANTED;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return has;
    }

    protected abstract int getLayoutResID();

    protected abstract void init();

    protected void initVb() {

    }

    protected void setLoadSir(View view) {
        if (mLoadService == null) {
            mLoadService = LoadSir.getDefault().register(view, new Callback.OnReloadListener() {
                @Override
                public void onReload(View v) {
                }
            });
        }
    }

    protected void showLoading() {
        if (mLoadService != null) {
            mLoadService.showCallback(LoadingCallback.class);
        }
    }

    protected void showEmpty() {
        showEmpty(null);
    }

    /**
     * 空态 + 自定义说明(如"该源不可用:插件缺少 X 类")。
     * <p>
     * LoadSir 的 EmptyCallback 视图是全 App 复用的同一份(view_empty.xml),所以这里
     * <b>每次都要把文案设回去</b>(tip 为空即恢复默认"暂无数据"),否则上一页留下的定制文案
     * 会跟着跑到别的页面。找不到该 TextView(布局换过)时静默降级为纯空态。
     */
    protected void showEmpty(String tip) {
        if (mLoadService != null) {
            mLoadService.showCallback(EmptyCallback.class);
            applyEmptyTip(tip);
        }
    }

    private void applyEmptyTip(String tip) {
        try {
            View layout = mLoadService.getLoadLayout();
            View tv = layout == null ? null : layout.findViewById(R.id.tv_empty_text);
            if (tv instanceof TextView) {
                ((TextView) tv).setText(tip == null || tip.isEmpty()
                        ? getString(R.string.empty_default_tip) : tip);
            }
        } catch (Throwable ignored) {
        }
    }

    protected void showSuccess() {
        if (null != mLoadService) {
            mLoadService.showSuccess();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 全局背景层:主题切换/背景图配置变更后重新应用;图源未变时为零开销
        try {
            if (shouldAttachPageBackground()) attachPageBackground();
        } catch (Throwable ignored) {
        }
        // 暂停页面恢复时的主题与输入框诊断。
        /* 临时停用页面恢复时的主题探针，避免定时采集与日志输出。
        try {
            final View probeRoot = getWindow() == null ? null : getWindow().getDecorView();
            if (probeRoot != null) {
                probeRoot.postDelayed(() -> com.github.tvbox.osc.theme.RadiusCheck.reportViews(
                        probeRoot, getClass().getSimpleName(),
                        new int[]{R.id.search, R.id.tvName, R.id.bottom_nav_surface,
                                R.id.my_surface_card, R.id.btn_live, R.id.btn_filter,
                                R.id.update_bubble, R.id.ivThumb, R.id.tvYear}), 1200);
                // 输入框的颜色只能"等它真的弹出来"才测得准(裸 inflate 不经过主题工厂,读到的是编译期值):
                // 这里延时再采一次**当前窗口里真实的 EditText**,给输入框一族颜色一个可核对的数字。
                probeRoot.postDelayed(() -> com.github.tvbox.osc.theme.RadiusCheck.reportInputs(
                        BaseActivity.this), 9000);
            }
        } catch (Throwable ignored) {
        }
        */
        // 全局更新悬浮圈:下载进行中时,当前页面顶部悬浮圆形进度钮(不依赖系统悬浮窗权限)
        try {
            com.github.tvbox.osc.update.UpdateFloatIndicator.get(this).attach(this);
        } catch (Throwable ignored) {
        }
        // 上次因"正在播放"挡下的明暗重建,等这页空闲了补上(否则它会一直停在翻明暗之前那套色)
        if (nightRecreatePending && allowRecreateOnNightChange()) {
            nightRecreatePending = false;
            if (com.github.tvbox.osc.theme.ThemeRuntime.followsSystem()
                    || com.github.tvbox.osc.theme.ThemeRuntime.runtimePalette() != null) {
                recreateForSystemNight();
            }
        }
    }

    // ------------------------------------------------------------------
    // 系统翻明暗时重铺页面
    // ------------------------------------------------------------------

    /** 建这页时系统的明暗(判断 onConfigurationChanged 里"是不是真的翻明暗了",而不是转屏/字号变化) */
    private int createdSystemNight = -1;
    /** 翻明暗时因"正在播放"挡下的重建:回到前台且空闲时补一次 */
    private boolean nightRecreatePending;

    private int systemNightNow() {
        try {
            return getApplicationContext().getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK;
        } catch (Throwable th) {
            return Configuration.UI_MODE_NIGHT_NO;
        }
    }

    /**
     * 本页在"跟随系统"翻明暗时是否允许<b>重建自己</b>。
     * <p>
     * 默认允许;播放中的页面(详情页预览播放器/直播页)应返回 false —— 翻明暗不该把正在看的片子
     * 重建掉,那次重建会记下来,等页面空闲回到前台时补做(见 {@link #onResume()})。
     */
    protected boolean allowRecreateOnNightChange() {
        return true;
    }

    /**
     * 主页/直播/详情在清单里声明了 {@code uiMode}(系统不重建它们),所以翻明暗只能自己处理。
     * 跟随系统时要换运行时调色板;固定主题也要重铺视图,避免系统刷新把颜色和形状盖回平台资源。
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        int night = newConfig == null ? systemNightNow()
                : newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK;
        handleSystemNightChange(night);
    }

    private void handleSystemNightChange(int night) {
        if (createdSystemNight < 0) {
            createdSystemNight = night;
            return;
        }
        if (night == createdSystemNight) return;
        createdSystemNight = night;
        // 跟随系统时换亮暗类型；显式内置主题和自定义主题也要重铺一次，防止系统配置刷新
        // 把既有 View 的颜色/形状退回平台资源。门槛是运行时调色板是否已装配。
        if (!com.github.tvbox.osc.theme.ThemeRuntime.followsSystem()
                && com.github.tvbox.osc.theme.ThemeRuntime.runtimePalette() == null) return;
        if (!allowRecreateOnNightChange()) {
            nightRecreatePending = true;
            return;
        }
        recreateForSystemNight();
    }

    private void recreateForSystemNight() {
        try {
            // post 一下:避开"在 onConfigurationChanged 里直接重建自己"的时序问题(框架还在派发这次配置变化)
            getWindow().getDecorView().post(() -> {
                try {
                    if (!isFinishing() && !isDestroyed()) recreate();
                } catch (Throwable ignored) {
                }
            });
            com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM,
                    "主题: 系统翻明暗,跟随系统重建页面 " + getClass().getSimpleName());
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            com.github.tvbox.osc.update.UpdateFloatIndicator.get(this).detach(this);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        AppManager.getInstance().finishActivity(this);
        try {
            com.github.tvbox.osc.update.UpdateFloatIndicator.get(this).detach(this);
        } catch (Throwable ignored) {
        }
    }

    public void jumpActivity(Class<? extends BaseActivity> clazz) {
        Intent intent = new Intent(mContext, clazz);
        startActivity(intent);
    }

    public void jumpActivity(Class<? extends BaseActivity> clazz, Bundle bundle) {
        if (DetailActivity.class.isAssignableFrom(clazz) && PlayConfig.getBackgroundPlayType() == 2) {
            //1.重新打开singleTask的页面(关闭小窗) 2.关闭画中画，重进detail再开启画中画会闪退
            ActivityUtils.finishActivity(DetailActivity.class);
        }
        Intent intent = new Intent(mContext, clazz);
        intent.putExtras(bundle);
        startActivity(intent);
    }

    protected String getAssetText(String fileName) {
        StringBuilder stringBuilder = new StringBuilder();
        // try-with-resources:原来 BufferedReader 从不关闭,每调用一次泄一个 fd
        // (注:本方法目前在仓内已无调用方,顺手把资源处理修对,避免以后接线时踩坑)
        try (BufferedReader bf = new BufferedReader(new InputStreamReader(getAssets().open(fileName)))) {
            String line;
            while ((line = bf.readLine()) != null) {
                stringBuilder.append(line);
            }
            return stringBuilder.toString();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return "";
    }

    @Override
    public float getSizeInDp() {
        return isBaseOnWidth() ? 360 : 720;
    }

    @Override
    public boolean isBaseOnWidth() {
        return true;
    }


    /**
     * 显示加载框（统一走 DialogCoordinator 生成）
     */
    public void showLoadingDialog() {
        showLoadingDialog(null);
    }

    /**
     * 显示加载框并带一行状态文本（用于"逐个探测/可能十几秒"的流程：让用户看到进度，
     * 而不是点了没反应；文本经 {@link #updateLoadingHint} 中途更新）
     */
    public void showLoadingDialog(CharSequence hint) {
        showLoadingDialog(hint, null);
    }

    /**
     * 显示**可取消**的加载框:{@code onCancel} 非空时加载框里出现"取消"按钮,点它执行回调
     * (调用方应在此作废在跑的任务,见 SubscriptionExporter.cancel()),之后需自行 dismissLoadingDialog()。
     * 传 null 即普通的阻塞式加载框(不显示取消)。
     */
    public void showLoadingDialog(CharSequence hint, Runnable onCancel) {
        // 加载框是阻塞态:先把输入法收起来再弹。
        // 输入框那边刚提交(如"添加订阅"确认)时键盘还立着 —— 加载框里的状态文字/取消键会被键盘挡住;
        // 弹窗窗口本身不被输入法压矮由 ui/dialog/PopupKeyboardPolicy 负责,这里管的是"别让键盘压着加载框"。
        try {
            com.blankj.utilcode.util.KeyboardUtils.hideSoftInput(this);
        } catch (Throwable ignored) {
        }
        if (loadingPopup == null) {
            loadingPopup = com.github.tvbox.osc.ui.dialog.DialogCoordinator.loading(this);
        }
        if (loadingPopup instanceof com.github.tvbox.osc.ui.dialog.LoadingDialog) {
            ((com.github.tvbox.osc.ui.dialog.LoadingDialog) loadingPopup).setOnCancel(onCancel);
        }
        loadingPopup.show();
        updateLoadingHint(hint);
    }

    /** 更新加载框状态文本（加载框未显示时空操作；须在主线程调用） */
    public void updateLoadingHint(CharSequence hint) {
        if (loadingPopup instanceof com.github.tvbox.osc.ui.dialog.LoadingDialog) {
            ((com.github.tvbox.osc.ui.dialog.LoadingDialog) loadingPopup).setHint(hint);
        }
    }

    /**
     * 隐藏加载框
     */
    public void dismissLoadingDialog() {
        if (loadingPopup != null && loadingPopup.isShow()) {
            loadingPopup.dismiss();
        }
    }

}
