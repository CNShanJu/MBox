package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.widget.CompoundButtonCompat;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.CastVideo;
import com.github.tvbox.osc.cast.CastMediaRelay;
import com.github.tvbox.osc.cast.DlnaController;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.server.RemoteServer;
import com.github.tvbox.osc.util.AppBubble;
import com.github.tvbox.osc.util.HeavyTaskUtil;
import com.lxj.xpopup.core.BasePopupView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** 发现同网段 DLNA 电视，或选择已配对的电脑浏览器。 */
public class CastListDialog extends AppCenterPopupView {
    /** 浏览器返回设备 id，DLNA 电视返回 null。 */
    public interface OnCastStarted {
        void onCastStarted(String browserDeviceId);
        default void onDlnaStarted(String deviceName) {
            onCastStarted(null);
        }
    }

    private final CastMediaRelay mediaRelay = new CastMediaRelay();
    private final AtomicLong castRequests = new AtomicLong();

    private final CastVideo castVideo;
    private final OnCastStarted started;
    private final Map<String, String> headers;
    private final String headerOrigin;
    private final List<DlnaController.Device> televisions = new ArrayList<>();
    private final List<RemoteServer.LanDevice> browsers = new ArrayList<>();
    private DlnaController dlna;
    private RadioGroup targets;
    private TextView hint;
    private TextView confirm;
    private TextView refresh;
    private TextView manualConnect;
    private TextView stopDlna;
    private BasePopupView manualAddressPopup;
    private String lastManualAddress = "";
    private boolean searching;
    private boolean waitingForAnnouncements;
    private boolean casting;
    private boolean commandSubmitted;
    private volatile boolean closed;
    private String searchError;
    private final Runnable refreshActiveCast = new Runnable() {
        @Override public void run() {
            if (closed || stopDlna == null) return;
            updateStopDlnaButton();
            stopDlna.postDelayed(this, 1000);
        }
    };

    private static final class Target {
        final String key;
        final DlnaController.Device television;
        final String browserId;

        Target(DlnaController.Device television) {
            this.key = "dlna:" + television.id;
            this.television = television;
            this.browserId = null;
        }

        Target(RemoteServer.LanDevice browser) {
            this.key = "browser:" + browser.id;
            this.television = null;
            this.browserId = browser.id;
        }
    }

    public CastListDialog(@NonNull Context context, CastVideo castVideo) {
        this(context, castVideo, null);
    }

    public CastListDialog(@NonNull Context context, CastVideo castVideo, OnCastStarted started) {
        this(context, castVideo, started, null);
    }

    public CastListDialog(@NonNull Context context, CastVideo castVideo, OnCastStarted started,
                          Map<String, String> headers) {
        this(context, castVideo, started, headers, null);
    }

    public CastListDialog(@NonNull Context context, CastVideo castVideo, OnCastStarted started,
                          Map<String, String> headers, String headerOrigin) {
        super(context);
        this.castVideo = castVideo;
        this.started = started;
        this.headers = headers == null ? null : new HashMap<>(headers);
        this.headerOrigin = headerOrigin;
    }

    @Override protected int getImplLayoutId() { return R.layout.dialog_cast; }

    @Override protected void onCreate() {
        super.onCreate();
        ((TextView) findViewById(R.id.title)).setText("投屏到电视或电脑");
        hint = findViewById(R.id.cast_hint);
        targets = findViewById(R.id.cast_targets);
        confirm = findViewById(R.id.btn_confirm);
        refresh = findViewById(R.id.cast_refresh);
        manualConnect = findViewById(R.id.cast_manual_connect);
        stopDlna = new TextView(getContext(), null, 0, R.style.TextButton);
        stopDlna.setTextColor(ContextCompat.getColor(getContext(), R.color.text_highlight));
        LinearLayout stopContainer = (LinearLayout) hint.getParent();
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        stopParams.topMargin = (int) (8 * getResources().getDisplayMetrics().density + 0.5f);
        stopContainer.addView(stopDlna, stopContainer.indexOfChild(hint), stopParams);
        stopDlna.setOnClickListener(v -> stopActiveDlnaCast());
        stopDlna.post(refreshActiveCast);
        dlna = new DlnaController(getContext().getApplicationContext());
        targets.setOnCheckedChangeListener((group, checkedId) -> updateButtons());
        refresh.setOnClickListener(v -> search());
        manualConnect.setOnClickListener(v -> openManualAddress());
        confirm.setOnClickListener(v -> onConfirm());
        findViewById(R.id.btn_cancel).setOnClickListener(v -> dismiss());

        View scroll = findViewById(R.id.scrollCastTargets);
        scroll.post(() -> {
            if (closed) return;
            int cap = getResources().getDisplayMetrics().heightPixels / 3;
            if (scroll.getHeight() > cap) {
                android.view.ViewGroup.LayoutParams lp = scroll.getLayoutParams();
                lp.height = cap;
                scroll.setLayoutParams(lp);
            }
        });
        updateBrowsers();
        renderTargets();
        search();
    }

    private void updateBrowsers() {
        browsers.clear();
        String uri = castVideo == null ? null : castVideo.getUri();
        if (uri == null || !(uri.startsWith("http://") || uri.startsWith("https://"))) return;
        if (ControlManager.get().lanState() != ControlManager.LAN_ACTIVE) return;
        for (RemoteServer.LanDevice device : ControlManager.get().connectedDevices()) {
            if ("browser".equals(device.kind)) browsers.add(device);
        }
    }

    private void search() {
        if (closed || casting) return;
        searching = true;
        waitingForAnnouncements = false;
        searchError = null;
        televisions.clear();
        updateBrowsers();
        renderTargets();
        dlna.search(new DlnaController.SearchCallback() {
            @Override public void onDevices(List<DlnaController.Device> found) {
                if (closed) return;
                televisions.clear();
                if (found != null) televisions.addAll(found);
                renderTargets();
            }

            @Override public void onFinished(String error) {
                if (closed) return;
                searching = false;
                searchError = error;
                updateHint();
                updateButtons();
            }

            @Override public void onWaitingForAnnouncements() {
                if (closed) return;
                waitingForAnnouncements = true;
                updateHint();
            }

            @Override public void onAnnouncementWaitFinished() {
                if (closed) return;
                waitingForAnnouncements = false;
                updateHint();
            }
        });
    }

    private void openManualAddress() {
        if (closed || casting) return;
        if (manualAddressPopup != null) manualAddressPopup.dismiss();
        manualAddressPopup = CastManualAddressDialog.show(getContext(), lastManualAddress,
                address -> {
                    manualAddressPopup = null;
                    if (closed) return;
                    lastManualAddress = address;
                    connectManual(address);
                });
    }

    private void connectManual(String address) {
        if (closed || casting) return;
        searching = true;
        waitingForAnnouncements = false;
        searchError = null;
        televisions.clear();
        updateBrowsers();
        renderTargets();
        dlna.connectManual(address, new DlnaController.SearchCallback() {
            @Override public void onDevices(List<DlnaController.Device> found) {
                if (closed) return;
                televisions.clear();
                if (found != null) televisions.addAll(found);
                renderTargets();
            }

            @Override public void onFinished(String error) {
                if (closed) return;
                searching = false;
                searchError = error;
                updateHint();
                updateButtons();
            }
        });
    }

    private void renderTargets() {
        String selected = null;
        View checked = targets.findViewById(targets.getCheckedRadioButtonId());
        if (checked != null && checked.getTag() instanceof Target)
            selected = ((Target) checked.getTag()).key;
        targets.removeAllViews();
        for (DlnaController.Device device : televisions)
            addTarget(new Target(device), "电视 · " + device.name, selected);
        for (RemoteServer.LanDevice device : browsers)
            addTarget(new Target(device), "电脑 · " + device.name + " · " + device.ip, selected);
        if (selected == null && targets.getChildCount() == 1)
            ((RadioButton) targets.getChildAt(0)).setChecked(true);
        updateHint();
        updateButtons();
    }

    private void addTarget(Target target, String label, String selected) {
        RadioButton option = new RadioButton(getContext());
        option.setId(View.generateViewId());
        option.setTag(target);
        option.setText(label);
        int color = ContextCompat.getColor(getContext(), R.color.text_foreground);
        option.setTextColor(color);
        CompoundButtonCompat.setButtonTintList(option, ColorStateList.valueOf(color));
        option.setPadding(8, 12, 8, 12);
        targets.addView(option);
        if (target.key.equals(selected)) option.setChecked(true);
    }

    private void updateHint() {
        if (casting) {
            hint.setText("正在向电视发送播放请求…");
        } else if (searching) {
            hint.setText("正在查找同一局域网内的 DLNA 接收端…");
        } else if (!televisions.isEmpty()) {
            hint.setText("选择电视即可投屏，无需在电视上输入配对码。电脑网页推送仍需单独配对。");
        } else if (waitingForAnnouncements) {
            hint.setText("仍在等待 DLNA 设备公告（最多约 1 分钟），也可点“直连”输入接收端地址。");
        } else if (!browsers.isEmpty()) {
            hint.setText("未找到 DLNA 电视。仍可选择已配对的电脑浏览器播放。");
        } else if (searchError != null && !searchError.isEmpty()) {
            hint.setText(searchError);
        } else {
            String uri = castVideo == null ? null : castVideo.getUri();
            hint.setText(uri != null && (uri.startsWith("file:") || uri.startsWith("content:"))
                    ? "未找到 DLNA 电视。可输入接收端 IP:端口直连，无需配对码。"
                    : "未找到 DLNA 电视。可输入接收端 IP:端口直连；电脑网页推送需另行配对。");
        }
    }

    private void updateButtons() {
        refresh.setEnabled(!searching && !casting);
        manualConnect.setEnabled(!casting);
        confirm.setEnabled(!casting && targets.getCheckedRadioButtonId() != -1);
        confirm.setText(casting ? "投屏中…" : "投 屏");
        updateStopDlnaButton();
    }

    private void updateStopDlnaButton() {
        if (stopDlna == null) return;
        CastMediaRelay.ActiveCast active = CastMediaRelay.activeCast();
        stopDlna.setVisibility(active == null ? View.GONE : View.VISIBLE);
        stopDlna.setEnabled(active != null && !active.stopping && !casting);
        if (active != null) stopDlna.setText(active.stopping ? "正在停止 DLNA 投屏…"
                : "停止 DLNA 投屏 · " + active.deviceName);
    }

    private void stopActiveDlnaCast() {
        CastMediaRelay.ActiveCast active = CastMediaRelay.activeCast();
        if (active == null || active.stopping || casting) return;
        if (!CastMediaRelay.cancelActiveCast(active.generation, (success, message) -> {
            if (closed) return;
            updateStopDlnaButton();
            AppBubble.toast(message);
        })) {
            updateStopDlnaButton();
            AppBubble.toast("投屏状态已更新，请重试");
        } else updateStopDlnaButton();
    }

    private void onConfirm() {
        View selected = targets.findViewById(targets.getCheckedRadioButtonId());
        Target target = selected == null ? null : (Target) selected.getTag();
        if (target == null || castVideo == null) return;
        if (target.television != null) {
            castToTelevision(target.television);
            return;
        }
        String id = target.browserId;
        if (ControlManager.get().pushToBrowser(id, castVideo.getName(), castVideo.getUri(),
                headers, headerOrigin)) {
            if (started != null) started.onCastStarted(id);
            else ControlManager.get().clearEpisodeCast();
            AppBubble.toast("已发送播放请求，请在电脑查看播放状态");
            dismiss();
        } else {
            AppBubble.toast(ControlManager.get().pushFailureMessage(id, castVideo.getUri()));
        }
    }

    private void castToTelevision(DlnaController.Device device) {
        casting = true;
        commandSubmitted = false;
        updateHint();
        updateButtons();
        String sourceUrl = castVideo.getUri();
        long request = castRequests.incrementAndGet();
        HeavyTaskUtil.getSerialExecutorService().execute(() -> {
            if (closed || request != castRequests.get()) return;
            final String televisionUrl;
            try {
                televisionUrl = mediaRelay.prepare(getContext().getApplicationContext(), sourceUrl,
                        headers, headerOrigin, device.localAddress);
            } catch (IOException error) {
                String reason = error.getMessage();
                String message = reason != null && reason.startsWith("Media source returned HTTP ")
                        ? "播放源返回 " + reason.substring("Media source returned ".length()) + "，无法投屏"
                        : "Media source returned a non-video document".equals(reason)
                        ? "播放源返回的不是视频内容，无法投屏"
                        : "Media format could not be identified for casting".equals(reason)
                        ? "无法识别播放源格式，请更换线路后重试"
                        : "当前媒体无法提供给电视，请检查局域网连接或更换播放源";
                post(() -> showCastFailure(message));
                return;
            }
            if (closed || request != castRequests.get()) {
                mediaRelay.stop();
                return;
            }
            final long relayGeneration = mediaRelay.generation();
            post(() -> {
                if (closed || request != castRequests.get()) return;
                commandSubmitted = true;
                dlna.cast(device, castVideo.getName(), televisionUrl,
                        castVideo.getPositionMs(), (success, message) -> {
                    boolean retained = success && mediaRelay.bindRenderer(relayGeneration, device);
                    if (closed || request != castRequests.get()) {
                        if (!success) mediaRelay.stop(relayGeneration);
                        return;
                    }
                    if (retained) {
                        if (started != null) started.onDlnaStarted(device.name);
                        AppBubble.toast("已发送到电视，请在电视上查看播放状态");
                        casting = false;
                        dismiss();
                    } else {
                        mediaRelay.stop(relayGeneration);
                        showCastFailure(success ? "投屏状态已更新，请重新选择设备"
                                : message == null || message.isEmpty() ? "电视未接受播放请求" : message);
                    }
                });
            });
        });
    }

    private void showCastFailure(String message) {
        if (closed) return;
        casting = false;
        updateHint();
        updateButtons();
        AppBubble.toast(message);
    }

    @Override protected void onDismiss() {
        closed = true;
        if (stopDlna != null) stopDlna.removeCallbacks(refreshActiveCast);
        if (manualAddressPopup != null) {
            manualAddressPopup.dismiss();
            manualAddressPopup = null;
        }
        if (casting) {
            castRequests.incrementAndGet();
            // Once SOAP is submitted, the TV may still accept Play after this dialog closes.
            // Its result callback will revoke the relay on failure; success keeps media alive.
            if (!commandSubmitted)
                HeavyTaskUtil.getSerialExecutorService().execute(mediaRelay::stop);
        }
        if (dlna != null) dlna.close();
        super.onDismiss();
    }
}
