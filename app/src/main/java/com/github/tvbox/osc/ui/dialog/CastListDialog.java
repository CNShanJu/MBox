package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.View;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.widget.CompoundButtonCompat;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.CastVideo;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.server.RemoteServer;
import com.github.tvbox.osc.util.AppBubble;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 选择一台已配对的浏览器，定向推送当前播放地址。 */
public class CastListDialog extends AppCenterPopupView {
    public interface OnCastStarted { void onCastStarted(String deviceId); }
    private final CastVideo castVideo;
    private final OnCastStarted started;
    private final Map<String, String> headers;

    public CastListDialog(@NonNull Context context, CastVideo castVideo) {
        this(context, castVideo, null);
    }

    public CastListDialog(@NonNull Context context, CastVideo castVideo, OnCastStarted started) {
        this(context, castVideo, started, null);
    }

    public CastListDialog(@NonNull Context context, CastVideo castVideo, OnCastStarted started,
                          Map<String, String> headers) {
        super(context);
        this.castVideo = castVideo;
        this.started = started;
        this.headers = headers == null ? null : new java.util.HashMap<>(headers);
    }

    @Override protected int getImplLayoutId() { return R.layout.dialog_cast; }

    @Override protected void onCreate() {
        super.onCreate();
        ((TextView) findViewById(R.id.title)).setText("推送到电脑播放");
        TextView hint = findViewById(R.id.cast_hint);
        RadioGroup targets = findViewById(R.id.cast_targets);
        TextView confirm = findViewById(R.id.btn_confirm);
        boolean active = ControlManager.get().lanState() == ControlManager.LAN_ACTIVE;
        List<RemoteServer.LanDevice> browsers = new ArrayList<>();
        if (active) {
            for (RemoteServer.LanDevice device : ControlManager.get().connectedDevices()) {
                if ("browser".equals(device.kind)) browsers.add(device);
            }
        }
        hint.setText(!active ? "先在设置里开启局域网服务并重启应用。"
                : browsers.isEmpty() ? "没有已配对的电脑。先在电脑打开服务地址并输入配对码。"
                : "选择接收视频的电脑。下一集会继续推送到同一台设备。");
        for (RemoteServer.LanDevice device : browsers) {
            RadioButton option = new RadioButton(getContext());
            option.setId(View.generateViewId());
            option.setTag(device.id);
            option.setText(device.name + " · " + device.ip);
            option.setTextColor(ContextCompat.getColor(getContext(), R.color.text_foreground));
            CompoundButtonCompat.setButtonTintList(option, ColorStateList.valueOf(
                    ContextCompat.getColor(getContext(), R.color.text_foreground)));
            option.setPadding(8, 12, 8, 12);
            targets.addView(option);
            if (browsers.size() == 1) option.setChecked(true);
        }
        View scroll = findViewById(R.id.scrollCastTargets);
        scroll.post(() -> {
            int cap = getResources().getDisplayMetrics().heightPixels / 3;
            if (scroll.getHeight() > cap) {
                android.view.ViewGroup.LayoutParams lp = scroll.getLayoutParams();
                lp.height = cap;
                scroll.setLayoutParams(lp);
            }
        });
        confirm.setVisibility(active && !browsers.isEmpty() ? View.VISIBLE : View.GONE);
        confirm.setEnabled(targets.getCheckedRadioButtonId() != -1);
        targets.setOnCheckedChangeListener((group, checkedId) -> confirm.setEnabled(checkedId != -1));
        confirm.setText("推送到选中设备");
        confirm.setOnClickListener(v -> {
            View option = targets.findViewById(targets.getCheckedRadioButtonId());
            String id = option == null ? null : (String) option.getTag();
            if (id != null && castVideo != null
                    && ControlManager.get().pushToBrowser(id, castVideo.getName(), castVideo.getUri(), headers)) {
                if (started != null) started.onCastStarted(id);
                else ControlManager.get().clearEpisodeCast();
                AppBubble.toast("已发送播放请求，请在电脑查看播放状态");
                dismiss();
            } else {
                AppBubble.toast(ControlManager.get().pushFailureMessage(id,
                        castVideo == null ? null : castVideo.getUri()));
            }
        });
        findViewById(R.id.btn_cancel).setOnClickListener(v -> dismiss());
    }
}
