package com.github.tvbox.osc.ui.activity;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.state.SystemState;
import com.github.tvbox.osc.state.SystemStateMonitor;
import com.github.tvbox.osc.util.NetworkIssueRouter;

/**
 * 网络不可用页（独立页面）。
 * <p>
 * 来往：由 {@link NetworkIssueRouter} 在"断网 + 真实请求失败"时拉起（含请求发到一半断网）；
 * 页面自己订阅系统网络状态单点 {@link SystemStateMonitor}，<b>一有网就自动 finish 回到原页面</b>。
 * <p>
 * 两个按钮：{@code 返回} = 直接回原页面；{@code 我知道了} = 回原页面并在<b>本次断网期间</b>不再自动弹出
 * （网络恢复后自动解除，下次断网照常提示）。样式全部取主题资源（bg_body/text_main/text_sub/colorPrimary/
 * bg_r_common_*），主题 JSON 改色自动跟随。
 */
public class NoNetworkActivity extends BaseActivity {

    /** 触发原因（仅用于排查展示，页面本身不依赖它） */
    public static final String EXTRA_REASON = "reason";

    private SystemStateMonitor.Listener mNetListener;

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_no_network;
    }

    @Override
    protected void init() {
        findViewById(R.id.btn_no_network_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_no_network_got_it).setOnClickListener(v -> {
            NetworkIssueRouter.dismissForThisOutage();
            finish();
        });
        // 有网 → 自动返回原页面（用户不用手动点按钮）
        mNetListener = e -> {
            if (e != null && SystemStateMonitor.TYPE_NETWORK.equals(e.type)
                    && !SystemStateMonitor.VAL_NONE.equals(e.value)) {
                finish();
            }
        };
        SystemStateMonitor.get().register(mNetListener, SystemStateMonitor.TYPE_NETWORK);
        // 拉起瞬间网络已恢复（例如刚好切回 Wi-Fi）：直接返回，不留一屏"假无网"
        if (!isOffline()) finish();
    }

    private static boolean isOffline() {
        try {
            SystemState state = SystemStateMonitor.get().getCurrentState();
            return state != null && SystemStateMonitor.VAL_NONE.equals(state.network);
        } catch (Throwable th) {
            return true; // 读不到状态时按"无网"处理，避免误报"有网"把用户弹回去
        }
    }

    @Override
    protected void onDestroy() {
        if (mNetListener != null) {
            try {
                SystemStateMonitor.get().unregister(mNetListener);
            } catch (Throwable ignored) {
            }
            mNetListener = null;
        }
        super.onDestroy();
    }
}
