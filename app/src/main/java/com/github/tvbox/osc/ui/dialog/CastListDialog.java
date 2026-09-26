package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.CastVideo;
import com.lxj.xpopup.core.CenterPopupView;

import org.jetbrains.annotations.NotNull;

/**
 * 投屏弹窗(桩实现)
 * <p>
 * DLNA 投屏功能暂不可用:原依赖 com.github.devin1014.DLNA-Cast:dlna-dmc:V1.0.0
 * 已从 JitPack 消失,原始实现备份于项目根目录 _backup_dlna/ 下。
 * 待找到可替代的 DLNA 库后,可恢复 _backup_dlna/ 中的原始实现并适配新库 API。
 */
public class CastListDialog extends AppCenterPopupView {

    public CastListDialog(@NonNull @NotNull Context context, CastVideo castVideo) {
        super(context);
        // castVideo 参数保留以兼容调用方,暂不使用
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_cast;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        TextView title = findViewById(R.id.title);
        if (title != null) {
            title.setText("投屏功能暂不可用");
        }
        View rv = findViewById(R.id.rv);
        if (rv != null) {
            rv.setVisibility(View.GONE);
        }
        View btnConfirm = findViewById(R.id.btn_confirm);
        if (btnConfirm != null) {
            btnConfirm.setVisibility(View.GONE);
        }
        View btnCancel = findViewById(R.id.btn_cancel);
        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> dismiss());
        }
    }
}
