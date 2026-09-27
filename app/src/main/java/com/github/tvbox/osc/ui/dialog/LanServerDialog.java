package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.blankj.utilcode.util.ConvertUtils;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.databinding.DialogLanServerBinding;
import com.github.tvbox.osc.databinding.ItemLanAddrBinding;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.util.AppBubble;

import java.util.List;

/**
 * 局域网服务弹窗:<b>回答"开了之后从哪个地址访问"</b>。
 *
 * <p>为什么要有它:设置行是"标题 + 开关"的横排(手机端一行塞不下地址),开关打开后用户
 * 就再也看不到任何地址信息 —— 只能自己猜路由器分配的 IP 与端口。所以这里把三件事一次说清:
 * <ul>
 *   <li><b>当前状态</b>:已生效(局域网可访问)/ 已开启但<b>重启应用后</b>生效 / 未开启(仅本机);</li>
 *   <li><b>访问地址</b>:逐个列出本机可被局域网访问的地址(形如 {@code http://192.168.1.23:9978/}),
 *       每行一个「复制」(纯文字、主题高亮色);取不到(手机没连 Wi‑Fi)时明说没取到,不糊弄;</li>
 *   <li><b>说明入口</b>:{@code R.string.setting_lan_server_tip} 的全文<b>不再整段铺在弹窗里</b>
 *       (用户反馈太碍眼),收成一行可点的「用它做什么?」,点开由 {@link TextTipDialog} 显示 ——
 *       与设置行标题长按看到的是同一份文案、同一个弹窗。</li>
 * </ul>
 *
 * <p>入口两处,同一个弹窗:①开关由关变开时自动弹一次(此刻最需要知道地址);
 * ②设置行标题长按随时再看(换网络后地址会变,回来看一眼即可)。
 *
 * <p>「立即重启应用」按钮只在"已开但未生效"(或"已关但端口还开着")时出现:开关是重启生效的,
 * 不给一键重启,用户只能自己去后台把应用杀掉 —— 这也是"开了却访问不了"的常见原因。
 * 它与「知道了」并排等宽,隐藏时「知道了」自动占满整排。
 */
public class LanServerDialog extends AppBottomPopupView {

    /** 点「立即重启应用」的回调:重启是页面级动作(要清任务栈/停服务),弹窗只表达意图 */
    public interface OnRestartRequest {
        void onRestart();
    }

    private final OnRestartRequest restartRequest;

    public LanServerDialog(@NonNull Context context, OnRestartRequest restartRequest) {
        super(context);
        this.restartRequest = restartRequest;
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_lan_server;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        DialogLanServerBinding b = DialogLanServerBinding.bind(getPopupImplView());

        int state = ControlManager.get().lanState();
        b.tvLanState.setText(stateText(state));
        b.tvLanState.setTextColor(ContextCompat.getColor(getContext(),
                state == ControlManager.LAN_ACTIVE ? R.color.text_highlight : R.color.text_sub_foreground));
        // 引导语跟着状态走:地址"现在能不能用"在四种状态下不一样,不能一律说"打开即可访问"
        b.tvLanLead.setText(leadText(state));

        fillAddresses(b, ControlManager.get().getLanAccessUrls());

        // "开关与当前实例不一致"的两个方向都要给一键重启:开了没重启=访问不了;关了没重启=端口还开着
        boolean pendingRestart = state == ControlManager.LAN_PENDING_RESTART
                || state == ControlManager.LAN_PENDING_CLOSE;
        b.btnLanRestart.setVisibility(pendingRestart ? View.VISIBLE : View.GONE);
        b.btnLanRestart.setOnClickListener(v -> {
            dismiss();
            if (restartRequest != null) restartRequest.onRestart();
        });
        b.btnLanOk.setOnClickListener(v -> dismiss());
        // 说明入口:整段"用它做什么…"不再铺在弹窗里,点这行(或它下面的箭头)才打开——
        // 复用设置行标题长按那个 TextTipDialog(同一份文案 setting_lan_server_tip,不再抄一份)
        b.llLanHelp.setOnClickListener(v -> showHelp());
    }

    /** 「用它做什么?」:与设置行标题长按看到的是同一个弹窗、同一份文案 */
    private void showHelp() {
        new com.lxj.xpopup.XPopup.Builder(getContext())
                .asCustom(new TextTipDialog(getContext(),
                        getContext().getString(R.string.lan_server_title),
                        getContext().getString(R.string.setting_lan_server_tip)))
                .show();
    }

    private CharSequence stateText(int state) {
        switch (state) {
            case ControlManager.LAN_ACTIVE:
                return getContext().getString(R.string.lan_server_state_active);
            case ControlManager.LAN_PENDING_RESTART:
                return getContext().getString(R.string.lan_server_state_pending);
            case ControlManager.LAN_PENDING_CLOSE:
                return getContext().getString(R.string.lan_server_state_pending_close);
            default:
                return getContext().getString(R.string.lan_server_state_off);
        }
    }

    /** 地址块上方的引导语:同样按状态区分"现在就能访问 / 重启后才能访问 / 开启重启后才能访问 / 还没收回" */
    private CharSequence leadText(int state) {
        switch (state) {
            case ControlManager.LAN_ACTIVE:
                return getContext().getString(R.string.lan_server_lead);
            case ControlManager.LAN_PENDING_RESTART:
                return getContext().getString(R.string.lan_server_lead_pending);
            case ControlManager.LAN_PENDING_CLOSE:
                return getContext().getString(R.string.lan_server_lead_pending_close);
            default:
                return getContext().getString(R.string.lan_server_lead_off);
        }
    }

    /**
     * 逐个铺地址行;一个都没取到时显示"没取到局域网 IP"的说明(而不是留个空白框或者写 0.0.0.0)。
     * 每一行都可点(整行或「复制」)复制该地址 —— 地址要拿到别的设备上敲/粘贴,复制是唯一实用动作。
     */
    private void fillAddresses(DialogLanServerBinding b, List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            b.tvLanAddrEmpty.setVisibility(View.VISIBLE);
            return;
        }
        b.tvLanAddrEmpty.setVisibility(View.GONE);
        LayoutInflater inflater = LayoutInflater.from(getContext());
        for (int i = 0; i < urls.size(); i++) {
            final String url = urls.get(i);
            ItemLanAddrBinding row = ItemLanAddrBinding.inflate(inflater, b.llLanAddrBox, false);
            row.tvLanAddr.setText(url);
            if (i > 0) {
                // 行间距只加在第二行起:卡片已有 12dp 内边距,首行再加会与上面的说明脱节
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) row.getRoot().getLayoutParams();
                lp.topMargin = ConvertUtils.dp2px(8);
                row.getRoot().setLayoutParams(lp);
            }
            View.OnClickListener copy = v -> {
                com.blankj.utilcode.util.ClipboardUtils.copyText(url);
                AppBubble.toast(getContext().getString(R.string.lan_server_copied) + " " + url);
            };
            row.getRoot().setOnClickListener(copy);
            row.btnLanCopy.setOnClickListener(copy);
            b.llLanAddrBox.addView(row.getRoot());
        }
    }
}
