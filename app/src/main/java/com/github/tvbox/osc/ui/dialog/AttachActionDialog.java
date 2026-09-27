package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.Utils;
import com.lxj.xpopup.XPopup;
import com.lxj.xpopup.core.AttachPopupView;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 长按气泡(统一的"贴着条目弹出几个动作"):<b>颜色全部走主题配置</b>。
 *
 * <p>为什么不用 XPopup 自带的 {@code asAttachList}:它的面/文字色来自库内固定样式,
 * 不吃我们的主题文件 —— 自定义主题下气泡会是一块"外来"的白/深底,看着很割裂。
 * 这里自己出布局:{@code bg_bubble}(主题悬浮面 bg_float,与底栏/标题栏/弹窗同一个面;圆角走小件档,
 * 与同屏的 chip 同档,见 {@code res/drawable/bg_bubble.xml} 的说明)+
 * 文字色按动作类型取主题色(普通 {@code text_main}、危险 {@code text_danger},与列表工具条上的"删除"同色)。
 *
 * <p>用法:
 * <pre>
 *   AttachActionDialog.show(anchorView, new String[]{"编辑", "设为默认", "删除"},
 *                           new int[]{NORMAL, NORMAL, DANGER}, pos -&gt; { ... });
 * </pre>
 */
public class AttachActionDialog extends AttachPopupView {

    /** 动作的文字配色 */
    public static final int NORMAL = 0;
    /** 危险动作(删除/清空这类不可逆的):用不带底色的危险文字色,与列表里的「删除」一致 */
    public static final int DANGER = 1;

    public interface OnActionSelected {
        void onSelected(int position);
    }

    private final List<String> actions = new ArrayList<>();
    private final List<Integer> kinds = new ArrayList<>();
    private OnActionSelected selected;

    public AttachActionDialog(@NonNull @NotNull Context context) {
        super(context);
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_attach_actions;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        // 换肤兜底:本类直接继承 XPopup 的 *PopupView,没走 AppBottom/Center/Drawer 那层壳,
        // 面板底不会被换肤注入覆盖到 —— 这里补同一趟扫描。
        // **注意范围**:这一趟只管气泡**内部**的行/文字/分割线;气泡那层面底不在这里,
        // 见 {@link #applyBg()}(XPopup 会把 impl 的底搬走,在这里改 impl 是白改)。
        try {
            com.github.tvbox.osc.theme.ThemeSweep.apply(getPopupImplView());
            com.github.tvbox.osc.theme.ThemeSweep.watchItems(getPopupImplView());
        } catch (Throwable ignored) {
        }
        LinearLayout container = findViewById(R.id.ll_actions);
        if (container == null) return;
        container.removeAllViews();
        for (int i = 0; i < actions.size(); i++) {
            container.addView(buildRow(container, i, actions.get(i), kinds.get(i)));
        }
    }

    /**
     * 气泡那层面底必须在这里接管,不能在 {@code onCreate} 里补:
     * XPopup 的 {@code AttachPopupView#applyBg} 会把 impl 视图自己的 background **搬到外层容器上**
     * 并把 impl 的底**置空**,而搬过去的那份是它自己从资源表**重新解析**出来的
     * (重新解析走的是编译期/内置主题那份 {@code @color/bg_float},换肤层拦不到 native 取色),
     * 于是自定义主题下气泡永远是内置白底/暗色深底(用户口径:"切换布局气泡的背景色没走卡片与悬浮层颜色")。
     * <p>
     * **只补色,不换整份 drawable**:这里用 {@code ThemeDrawables.applyBackground} 装回一份重建的
     * {@code bg_bubble} 也能上色,但圆角/描边/尺寸就改成重建那份的了(用户口径:"tips 也是圆角不对了")。
     * {@link com.github.tvbox.osc.theme.ThemeSweep} 是就地改已有 drawable 的纯色,几何一律原样保留。
     * <p>
     * 两层各用各的:气泡**内部**(行/文字/分割线)走按值比对的 {@code apply};那层**面**走
     * {@code applyFloatFace} —— 按设计就知道它是 {@code bg_float},不必去猜搬过来的颜色等不等于内置那份
     * (只调 {@code apply} 时圆角对、颜色却还是内置档,就是这个原因)。
     */
    @Override
    protected void applyBg() {
        super.applyBg();
        try {
            com.github.tvbox.osc.theme.ThemeSweep.apply(attachPopupContainer);
            com.github.tvbox.osc.theme.ThemeSweep.applyFloatFace(attachPopupContainer);
        } catch (Throwable ignored) {
        }
    }

    private TextView buildRow(LinearLayout parent, final int position, String text, int kind) {
        TextView tv = new TextView(getContext());
        int padH = dp(18);
        int padV = dp(11);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        tv.setPadding(padH, padV, padH, padV);
        // 只有一个动作时(如搜索页 ⋮ 的「切换布局」)整行居中:气泡是按内容宽度弹出的,
        // 左对齐会让那一个词贴着左边、右边空一截(用户口径:"文字没有水平居中")
        tv.setGravity(actions.size() <= 1 ? Gravity.CENTER : Gravity.CENTER_VERTICAL);
        tv.setTextSize(15f);
        tv.setSingleLine(true);
        tv.setText(text);
        tv.setTextColor(androidx.core.content.ContextCompat.getColor(getContext(),
                kind == DANGER ? R.color.text_danger : R.color.text_foreground));
        // 触摸反馈用主题点击态(与其它可点条目一致),不要另开一套颜色。
        // 必须走 ThemeDrawables 这条主题入口:setBackgroundResource 取的是**编译期**那份 drawable,
        // 自定义主题下这一行的底会停在内置色(ThemeDrawables 的类注释里记的就是这个坑)。
        com.github.tvbox.osc.theme.ThemeDrawables.applyBackground(tv, R.drawable.ripple_round_background);
        tv.setOnClickListener(v -> {
            FastClickCheckUtil.check(v);
            int pos = position;
            dismiss();
            if (selected != null) selected.onSelected(pos);
        });
        return tv;
    }

    public void setActions(String[] titles, int[] actionKinds, OnActionSelected listener) {
        actions.clear();
        kinds.clear();
        if (titles != null) {
            for (int i = 0; i < titles.length; i++) {
                actions.add(titles[i]);
                kinds.add(actionKinds != null && i < actionKinds.length ? actionKinds[i] : NORMAL);
            }
        }
        this.selected = listener;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * 弹出气泡。
     *
     * @param anchor   贴着哪个视图弹(通常是长按的那一行)
     * @param titles   动作文字
     * @param kinds    {@link #NORMAL} / {@link #DANGER},与 titles 一一对应
     */
    public static void show(View anchor, String[] titles, int[] kinds, OnActionSelected listener) {
        if (anchor == null || titles == null || titles.length == 0) return;
        AttachActionDialog dialog = new AttachActionDialog(anchor.getContext());
        dialog.setActions(titles, kinds, listener);
        new XPopup.Builder(anchor.getContext())
                .isDarkTheme(Utils.isAppDarkTheme())
                .hasShadowBg(false)
                .atView(anchor)
                .asCustom(dialog)
                .show();
    }
}
