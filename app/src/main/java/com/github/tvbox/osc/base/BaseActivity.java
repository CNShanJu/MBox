package com.github.tvbox.osc.base;

import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

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
import com.hjq.bar.OnTitleBarListener;
import com.hjq.bar.TitleBar;
import com.kingja.loadsir.callback.Callback;
import com.kingja.loadsir.core.LoadService;
import com.kingja.loadsir.core.LoadSir;
import com.lxj.xpopup.core.BasePopupView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import me.jessyan.autosize.internal.CustomAdapt;

public abstract class BaseActivity extends AppCompatActivity implements CustomAdapt, OnTitleBarListener {
    protected Context mContext;
    private LoadService mLoadService;

    private ImmersionBar mImmersionBar;
    private TitleBar mTitleBar;
    private BasePopupView loadingPopup;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (getLayoutResID()==-1){
            initVb();
        }else {
            setContentView(getLayoutResID());
        }
        mContext = this;
        AppManager.getInstance().addActivity(this);
        // 全局页面背景层("body"底图):挂到内容容器最底层,所有页面透明处即显示背景图
        // 图源/遮罩/缩放位置统一走系统配置门面(SystemConfig)组装,设置页改完各页 onResume 自动套用
        try {
            attachPageBackground();
        } catch (Throwable ignored) {
        }
        initStatusBar();
        initTitleBar();
        init();
        if (!App.getInstance().isNormalStart){
            AppUtils.relaunchApp(true);
        }
    }


    private void initStatusBar(){
        ImmersionBar.with(this)
                .statusBarDarkFont(!Utils.isDarkTheme())
                .titleBar(findTitleBar(getWindow().getDecorView().findViewById(android.R.id.content)))
                .navigationBarColor(android.R.color.transparent)
                .init();
    }

    private void initTitleBar(){
        if (getTitleBar() != null) {
            getTitleBar().setOnTitleBarListener(this);
        }
    }

    /**
     * 递归获取 ViewGroup 中的 TitleBar 对象
     */
    private TitleBar findTitleBar(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View view = group.getChildAt(i);
            if ((view instanceof TitleBar)) {
                return (TitleBar) view;
            } else if (view instanceof ViewGroup) {
                TitleBar titleBar = findTitleBar((ViewGroup) view);
                if (titleBar != null) {
                    return titleBar;
                }
            }
        }
        return null;
    }

    private TitleBar getTitleBar() {
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
    private void attachPageBackground() {
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
        if (null != mLoadService) {
            mLoadService.showCallback(EmptyCallback.class);
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
            attachPageBackground();
        } catch (Throwable ignored) {
        }
        // 全局更新悬浮圈:下载进行中时,当前页面顶部悬浮圆形进度钮(不依赖系统悬浮窗权限)
        try {
            com.github.tvbox.osc.update.UpdateFloatIndicator.get(this).attach(this);
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
        try {
            AssetManager assets = getAssets();
            BufferedReader bf = new BufferedReader(new InputStreamReader(assets.open(fileName)));
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

    @Override
    public void onLeftClick(TitleBar titleBar) {
        finish();
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