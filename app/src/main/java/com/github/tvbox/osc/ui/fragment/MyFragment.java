package com.github.tvbox.osc.ui.fragment;

import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;

import com.blankj.utilcode.util.AppUtils;
import com.blankj.utilcode.util.ClipboardUtils;
import com.github.tvbox.osc.util.AppBubble;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseVbFragment;
import com.github.tvbox.osc.databinding.FragmentMyBinding;
import com.github.tvbox.osc.config.SystemConfig;
import com.github.tvbox.osc.ui.activity.CollectActivity;
import com.github.tvbox.osc.ui.activity.DetailActivity;
import com.github.tvbox.osc.ui.activity.DownloadActivity;
import com.github.tvbox.osc.ui.activity.HistoryActivity;
import com.github.tvbox.osc.ui.activity.LiveActivity;
import com.github.tvbox.osc.ui.activity.MovieFoldersActivity;
import com.github.tvbox.osc.ui.activity.SettingActivity;
import com.github.tvbox.osc.ui.dialog.AboutDialog;
import com.github.tvbox.osc.ui.dialog.DialogCoordinator;
import com.github.tvbox.osc.ui.dialog.DialogStyle;
import com.github.tvbox.osc.ui.dialog.PopupKeyboardPolicy;
import com.github.tvbox.osc.update.UpdateCheck;
import com.github.tvbox.osc.update.UpdateInfo;
import com.hjq.permissions.OnPermissionCallback;
import com.hjq.permissions.Permission;
import com.hjq.permissions.XXPermissions;
import com.lxj.xpopup.XPopup;
import com.lxj.xpopup.core.BasePopupView;
import com.lxj.xpopup.interfaces.SimpleCallback;

import java.util.Arrays;
import java.util.List;

/**
 * @author pj567
 * @date :2021/3/9
 * @description:
 */
public class MyFragment extends BaseVbFragment<FragmentMyBinding> {

    private boolean updateCheckInProgress;

    @Override
    protected void init() {
        mBinding.tvVersion.setText("v"+ AppUtils.getAppVersionName());

        mBinding.addrPlay.setOnClickListener(v ->{
            new XPopup.Builder(getContext())
                    .maxWidth(DialogStyle.centerWidthPx(getContext()))
                    .moveUpToKeyboard(false)
                    .setPopupCallback(new SimpleCallback() {
                        @Override
                        public void onCreated(BasePopupView popupView) {
                            PopupKeyboardPolicy.afterFocus(popupView);
                        }
                    })
                    .asInputConfirm("播放", "", isPush(ClipboardUtils.getText().toString())?ClipboardUtils.getText():"", "地址", text -> {
                        if (!TextUtils.isEmpty(text)){
                            Intent newIntent = new Intent(mContext, DetailActivity.class);
                            newIntent.putExtra("id", text);
                            newIntent.putExtra("sourceKey", "push_agent");
                            newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            startActivity(newIntent);
                        }
                    }, null, R.layout.dialog_input).show();
        });
        //mBinding.tvLive.setOnClickListener(v -> jumpActivity(LivePlayActivity.class));
        mBinding.tvLive.setOnClickListener(v -> jumpActivity(LiveActivity.class));

        mBinding.tvSetting.setOnClickListener(v -> jumpActivity(SettingActivity.class));

        mBinding.tvHistory.setOnClickListener(v -> jumpActivity(HistoryActivity.class));
        mBinding.llCollect.setOnClickListener(v -> jumpActivity(CollectActivity.class));

        mBinding.tvDownload.setOnClickListener(v -> jumpActivity(DownloadActivity.class));

        mBinding.tvLocal.setOnClickListener(v -> {
            if (!XXPermissions.isGranted(mContext, Permission.MANAGE_EXTERNAL_STORAGE)) {
                showPermissionTipPopup();
            } else {
                jumpActivity(MovieFoldersActivity.class);
            }
        });

        mBinding.llCheckUpdate.setOnClickListener(v -> checkUpdate());

        mBinding.llAbout.setOnClickListener(v -> {
            // AboutDialog 是 AppBottomPopupView(底部抽屉):必须走 DialogCoordinator.bottom —— 它带的
            // isViewMode(true) + hasNavigationBar(false) 才是"和其它抽屉同款"的弹法(贴底、不留导航栏缝、
            // 手势条不闪)。原来走 center(...)(它只是 asCustom,不改位置但**少了这两个参数**),
            // 结果是同一个抽屉面板在不同入口下弹出位置/底边观感不一致(用户口径:"关于的抽屉圆角和别的抽屉不一致")。
            DialogCoordinator.bottom(mActivity, new AboutDialog(mActivity), 0).show();
        });
    }

    private void checkUpdate() {
        if (updateCheckInProgress) return;
        updateCheckInProgress = true;
        UpdateCheck.check(mActivity, new UpdateCheck.Listener() {
            @Override
            public void onChecking() {
            }

            @Override
            public boolean onResult(UpdateInfo newVersion) {
                updateCheckInProgress = false;
                if (!isResumed() || !getUserVisibleHint()) return true;
                if (newVersion == null) {
                    AppBubble.toast("已是最新版本");
                    return true;
                }
                return false; // 复用现有更新说明弹窗，由 UpdateCheck 显示。
            }

            @Override
            public void onFailed(String message) {
                updateCheckInProgress = false;
                if (isResumed() && getUserVisibleHint()) AppBubble.toast(message);
            }
        });
    }

    private void showPermissionTipPopup(){
        // 统一主题化确认弹窗(替代 XPopup 默认 asConfirm)
        com.github.tvbox.osc.ui.dialog.ConfirmDialog.show(mActivity, "提示",
                "为了播放视频、音频等,我们需要访问您设备文件的读写权限", "去授权", this::getPermission);
    }

    private void getPermission(){
        XXPermissions.with(this)
                .permission(Permission.MANAGE_EXTERNAL_STORAGE)
                .request(new OnPermissionCallback() {
                    @Override
                    public void onGranted(List<String> permissions, boolean all) {
                        if (all) {
                            jumpActivity(MovieFoldersActivity.class);
                        }else {
                            AppBubble.toast("请授予所需权限");
                        }
                    }

                    @Override
                    public void onDenied(List<String> permissions, boolean never) {
                        if (never) {
                            AppBubble.toast("请在系统设置中授予文件权限");
                            // 如果是被永久拒绝就跳转到应用权限系统设置页面
                            XXPermissions.startPermissionActivity(mActivity, permissions);
                        } else {
                            AppBubble.toast("获取权限失败");
                            showPermissionTipPopup();
                        }
                    }
                });
    }

    private boolean isPush(String text) {
        return !TextUtils.isEmpty(text) && Arrays.asList("smb", "http", "https", "thunder", "magnet", "ed2k", "mitv", "jianpian").contains(Uri.parse(text).getScheme());
    }

}
