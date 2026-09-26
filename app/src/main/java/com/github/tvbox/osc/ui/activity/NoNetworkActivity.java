package com.github.tvbox.osc.ui.activity;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.state.SystemStateMonitor;
import com.github.tvbox.osc.util.NetworkIssueRouter;

/**
 * 网络不可用页（独立页面）。
 * <p>
 * 来往：由 {@link NetworkIssueRouter} 在"断网 + 真实请求失败"时拉起（含请求发到一半断网）；
 * 页面自己订阅系统网络状态单点 {@link SystemStateMonitor}，<b>一有网就自动 finish 回到原页面</b>。
 * <p>
 * 两个按钮：{@code 返回} = 直接回原页面；{@code 我知道了} = 回原页面并在一段时间内不再自动弹出
 * （网络恢复后自动解除，下次断网照常提示）。样式全部取主题资源（bg_body/text_main/text_sub/colorPrimary/
 * bg_r_common_*），主题 JSON 改色自动跟随。
 * <p>
 * 自动返回有<b>最短停留</b>与"白弹回报"：拉起瞬间就发现已有链路时不再"闪一下就走"，
 * 并回报给路由做自我抑制（连续两次弹出即返回 → 抑制到下次网络变化），避免和页面重试组成节拍器。
 */
public class NoNetworkActivity extends BaseActivity {

    /** 触发原因（显示为副标题，同时进日志） */
    public static final String EXTRA_REASON = "reason";

    /** 自动返回的最短停留：避免"弹出来立刻自己走"（用户只看到闪屏，还会与页面重试形成 3s 节拍） */
    private static final long MIN_DWELL_MS = 1200L;

    private SystemStateMonitor.Listener mNetListener;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private long shownAt;
    /** 自动返回只处理一次（防重入，也避免与用户手点按钮打架） */
    private boolean autoFinished;

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_no_network;
    }

    @Override
    protected void init() {
        shownAt = SystemClock.uptimeMillis();
        findViewById(R.id.btn_no_network_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_no_network_got_it).setOnClickListener(v -> {
            NetworkIssueRouter.dismissForThisOutage();
            finish();
        });
        // 原因副标题：反馈/排查时能一眼看到"为什么弹了这一屏"（原来 reason 只进日志，页面上没有任何线索）
        TextView reasonView = findViewById(R.id.tv_no_network_reason);
        String reason = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_REASON);
        if (reasonView != null) {
            String text = reason == null ? "" : reason.trim();
            if (text.isEmpty()) {
                reasonView.setVisibility(View.GONE);
            } else {
                reasonView.setVisibility(View.VISIBLE);
                reasonView.setText(getString(R.string.no_network_reason, text));
            }
        }
        // 有网 → 自动返回原页面（用户不用手动点按钮）
        mNetListener = e -> {
            if (e != null && SystemStateMonitor.TYPE_NETWORK.equals(e.type)
                    && !SystemStateMonitor.VAL_NONE.equals(e.value)) {
                autoFinish("网络已恢复");
            }
        };
        SystemStateMonitor.registerSafe(mNetListener, SystemStateMonitor.TYPE_NETWORK);
        // 拉起瞬间网络已恢复（例如刚好切回 Wi-Fi）：也走 autoFinish（最短停留 + 白弹回报），
        // 不再"瞬间自己关掉"（那样用户只会看到闪屏，且掩盖了"路径判定不对"的真问题）
        if (!isOffline()) autoFinish("拉起时已有链路");
    }

    /** 自动返回：至少停留 {@link #MIN_DWELL_MS}，并把"白弹"回报给路由（连续两次即自我抑制） */
    private void autoFinish(String why) {
        if (autoFinished) return;
        autoFinished = true;
        long elapsed = SystemClock.uptimeMillis() - shownAt;
        long delay = Math.max(0L, MIN_DWELL_MS - elapsed);
        android.util.Log.i("TVBox-Net", "无网络页自动返回(" + why + ")，延迟 " + delay + "ms");
        NetworkIssueRouter.reportAutoDismissed();
        mHandler.postDelayed(() -> {
            if (!isFinishing() && !isDestroyed()) finish();
        }, delay);
    }

    private static boolean isOffline() {
        // 统一走系统状态单点的判定（原来本类 catch 返 true、GridFragment 那份 catch 返 false，同一语义两处相反）
        return SystemStateMonitor.isOfflineNow();
    }

    @Override
    protected void onDestroy() {
        mHandler.removeCallbacksAndMessages(null);
        SystemStateMonitor.unregisterSafe(mNetListener);
        mNetListener = null;
        super.onDestroy();
    }
}
