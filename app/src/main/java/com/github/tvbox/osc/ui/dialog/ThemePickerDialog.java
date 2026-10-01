package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.theme.ThemeDef;
import com.github.tvbox.osc.bean.theme.ThemeType;
import com.github.tvbox.osc.storage.theme.ThemeStore;
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter;
import com.github.tvbox.osc.util.AppBubble;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 「主题颜色」弹窗:跟随系统 / 浅色 / 深色 / 用户自定义主题,底部固定一个「自定义主题颜色」入口。
 *
 * <h3>观感:复用 SelectDialog,不另起一套</h3>
 * 面板、标题、关闭钮、列表行(左侧勾选框)、间距与高度分档全部来自 {@link SelectDialog} 与
 * {@code item_dialog_select.xml} —— 与"加载动画""同时下载任务数"这些设置弹窗逐像素同源。
 * 只有一处是"本弹窗特有"的,且走共享组件的**可选**能力:
 * <ul>
 *   <li><b>固定页脚</b>({@link #setFooterView}):「自定义主题颜色」入口 ——
 *       它是**动作**不是选项,所以不放进列表(否则会跟着主题列表一起滚,还得伪装成一行"带勾选框的选项"),
 *       放在页脚始终可见,文字居中、带"＋"图标,一眼可辨。</li>
 * </ul>
 * 列表行还有两处标记,都不挤占行布局:一个是<b>居中背景图标</b>({@link SelectDialogAdapter.RowStyle},
 * 太阳=亮色 / 月亮=暗色,当背景水印);一个是<b>行尾标记</b>(「亮色默认」/「暗色默认」/「使用中」,
 * 居右垂直居中,内置浅色/深色与用户主题同一套口径)。"当前生效的是哪个"不再单列副标题说明 ——
 * 行尾的「使用中」标记就是答案,两处重复只会互相打架。
 *
 * <h3>列表构成</h3>
 * <pre>
 *   跟随系统              ← 按系统明暗取"该类型的默认主题"(「默认」只在这个模式下参与解析)
 *   浅色 / 深色            ← 强制<b>内置</b>亮/暗(不受默认主题影响);它仍是该类型默认时行尾标
 *                           「亮色默认」/「暗色默认」,长按可把被自定义主题占走的默认抢回来
 *   [用户主题…]            ← 行尾按同一口径标「…默认」/「使用中」
 *   ──────────────
 *   ＋ 自定义主题颜色       ← 固定页脚(不随列表滚动),进编辑页新建
 * </pre>
 *
 * <h3>草稿式提交</h3>
 * 主题生效要走重启应用(与既有的浅色/深色切换同一条链路),所以本弹窗里的一切操作都<b>不立即落盘</b>,
 * 等弹窗关闭时由宿主调 {@link #commitIfDirty()} 一次性提交,再决定要不要重启 ——
 * 避免"删掉正在用的主题 → 界面先闪一下旧色/背景先变,再重启"的半生效状态。
 */
public class ThemePickerDialog extends SelectDialog<ThemePickerDialog.Row> {

    /** 列表行的形态 */
    public static final class Row {
        static final int KIND_FOLLOW = 0;
        static final int KIND_LIGHT = 1;
        static final int KIND_DARK = 2;
        static final int KIND_USER = 3;

        final int kind;
        /** 用户主题的 id(kind=USER 时有效) */
        final String themeId;
        /** 行显示文字(**不带任何标记**:标记由 {@link #markersOf} 单独加,免得两处拼串) */
        final String text;
        /** 该行的主题类型;null = 不画类型图标(跟随系统) */
        final ThemeType type;
        /**
         * 行尾的高亮小字标记:「亮色默认」/「暗色默认」= 它是<b>那个类型</b>的默认主题
         * (只在「跟随系统」模式下参与解析);「使用中」= 它是当前正在生效的那个
         * (看落盘状态,不看草稿 —— 刚打开弹窗也标,用户才知道现在用的是哪个)。
         * 由行布局里独立的尾视图承载(居右 + 垂直居中),不拼进行名字里
         * (拼进去会变成"浅色 · 暗夜紫"这种看不懂的行),内置浅色/深色与用户主题同一套口径。
         */
        final String markers;

        Row(int kind, String themeId, String text, ThemeType type, String markers) {
            this.kind = kind;
            this.themeId = themeId == null ? "" : themeId;
            this.text = text == null ? "" : text;
            this.type = type;
            this.markers = markers == null ? "" : markers;
        }
    }

    /** 宿主回调:两件事都跳二级页,所以由宿主决定怎么跳 */
    public interface Listener {
        /** 编辑一个已有主题 */
        void onEditTheme(ThemeDef def);

        /** 新建一个主题 */
        void onCreateTheme();
    }

    private Listener listener;

    /** 选中的草稿(不落盘) */
    private ThemeStore.Selection draft;
    /** 草稿里待删除的主题 id */
    private final Set<String> pendingDeletes = new HashSet<>();
    /** 用户是否在列表里点过选择(点过就以草稿为准,没点过就以落盘状态为准,避免覆盖删除顺带做的修正) */
    private boolean selectionTouched;
    /** 用户是否在列表里改过"默认主题" */
    private boolean defaultTouched;
    /** 打开弹窗那一刻的"生效配色指纹";关闭时比对它决定要不要重启(见 commitIfDirty) */
    private String openFingerprint = "";

    private final List<Row> rows = new ArrayList<>();
    private int selectedRow = 0;

    public ThemePickerDialog(@NonNull @NotNull Context context) {
        super(context);
        draft = ThemeStore.selection();
        openFingerprint = ThemeStore.activePaletteFingerprint();
        setTip("主题颜色");
        setFooterView(buildCreateEntry());
        // 长按出气泡:用户主题(编辑 / 设为默认 / 删除);内置浅色/深色(把被占走的"该类型默认"抢回来)
        setOnItemLongClickListener((value, position, itemView) -> {
            if (value == null) return false;
            if (value.kind == Row.KIND_USER) {
                showItemBubble(itemView, value);
                return true;
            }
            if (value.kind == Row.KIND_LIGHT || value.kind == Row.KIND_DARK) {
                return showBuiltinBubble(itemView, value);
            }
            return false;
        });
        // 每行背景一个居中图标(太阳=亮色 / 月亮=暗色):当背景水印,不动行布局
        setRowStyle(new SelectDialogAdapter.RowStyle<Row>() {
            @Override
            public int rowIcon(Row value) {
                if (value == null || value.type == null) return 0;
                return value.type.isDark() ? R.drawable.ic_theme_dark : R.drawable.ic_theme_light;
            }

            @Override
            public int rowIconTint(Row value) {
                // 与行文字同色；背景水印的透明度只由 rowIconAlpha 控制一次。
                return ContextCompat.getColor(getContext(), R.color.text_main);
            }

            @Override
            public int rowIconAlpha(Row value) {
                // 50% 文字色：亮暗主题下都能认出太阳/月亮，仍低于正文层。
                return 128;
            }
        });
        rebuildRows();
        setAdapter(new SelectDialogAdapter.SelectDialogInterface<Row>() {
            @Override
            public void click(Row value, int pos) {
                onRowClicked(value, pos);
            }

            @Override
            public CharSequence getDisplay(Row val) {
                return val == null ? "" : val.text;
            }

            @Override
            public CharSequence getMarkers(Row val) {
                return val == null ? "" : val.markers;
            }
        }, new androidx.recyclerview.widget.DiffUtil.ItemCallback<Row>() {
            @Override
            public boolean areItemsTheSame(@NonNull Row o, @NonNull Row n) {
                return o.kind == n.kind && o.themeId.equals(n.themeId);
            }

            @Override
            public boolean areContentsTheSame(@NonNull Row o, @NonNull Row n) {
                return o.kind == n.kind && o.themeId.equals(n.themeId)
                        && o.text.equals(n.text) && o.markers.equals(n.markers);
            }
        }, rows, selectedRow);
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        // 基类的 onCreate 是在"还没有数据"时量的高度,这里数据到位了,再按同一套口径夹一次
        // (否则小屏上列表会顶着布局里的 410dp 上限,被弹窗外框裁掉)
        clampListHeightToFit();
    }

    /**
     * 固定页脚:动作入口(文字 + "＋"图标<b>作为一个整体居中</b>),不随列表滚动,
     * 也不假装成"可勾选的选项行"。
     *
     * <p><b>为什么不用 {@code setCompoundDrawablesWithIntrinsicBounds}</b>:TextView 的复合图标
     * 是贴在<b>文本布局的左边缘</b>的,不参与 gravity 居中 —— 于是文字居中了、"＋"却孤零零留在最左侧
     * (这正是之前的故障)。改成横向 LinearLayout + {@code gravity=center}:图标与文字一起被当成一组居中。
     */
    private View buildCreateEntry() {
        Context ctx = getContext();
        int color = ContextCompat.getColor(ctx, R.color.text_foreground);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        // 底:这是个"动作入口",不是列表行/卡片 —— 用全站小动作键的统一底
        // (描边 + 小圆角 = bg_r_common_stroke_primary,与联想标签/播放器面板那些键同款)。
        // 原来用 bg_small_round_gray(卡片底色 + 卡片圆角):在主题色偏亮/偏红时,
        // 这块就是一大坨实心色块,而且卡片圆角放在 ~48dp 高的动作行上看着像胶囊
        // (用户口径:"这个按钮样式又出来了…看着奇奇怪怪的")。
        com.github.tvbox.osc.theme.ThemeDrawables.applyBackground(row, R.drawable.bg_r_common_stroke_primary);
        int padV = dp(13);
        row.setPadding(dp(12), padV, dp(12), padV);

        ImageView icon = new ImageView(ctx);
        icon.setImageResource(R.drawable.ic_add_24);
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(color));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(18), dp(18));
        iconLp.gravity = Gravity.CENTER_VERTICAL;
        icon.setLayoutParams(iconLp);

        TextView tv = new TextView(ctx);
        tv.setText("自定义主题颜色");
        tv.setTextSize(15f);
        tv.setSingleLine(true);
        tv.setTextColor(color);
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tvLp.leftMargin = dp(6);
        tvLp.gravity = Gravity.CENTER_VERTICAL;
        tv.setLayoutParams(tvLp);

        row.addView(icon);
        row.addView(tv);
        row.setOnClickListener(v -> {
            com.github.tvbox.osc.util.FastClickCheckUtil.check(v);
            if (listener != null) listener.onCreateTheme();
        });
        return row;
    }

    // ------------------------------------------------------------------
    // 列表
    // ------------------------------------------------------------------

    /** 按"落盘主题 + 当前草稿(含待删除)"重算行数据与勾选位置 */
    private void rebuildRows() {
        rows.clear();
        boolean customValid = !draft.customId.isEmpty()
                && ThemeStore.find(draft.customId) != null
                && !pendingDeletes.contains(draft.customId);

        // 草稿里待删除的主题不能算"默认"(落盘还没删,但列表上它已经没了)
        String brightDefault = pendingDeletes.contains(draft.defaultBrightId) ? "" : draft.defaultBrightId;
        String darkDefault = pendingDeletes.contains(draft.defaultDarkId) ? "" : draft.defaultDarkId;
        // 「使用中」看**落盘的当前生效主题**(不是草稿):用户切了还没重启时,
        // 得能一眼看出"现在用的是哪个、等下要用的是哪个"。
        // 刚打开弹窗(草稿==落盘)也照标 —— 否则"现在用的是哪个"就没了答案。
        ThemeDef active = ThemeStore.resolveActive();
        String activeId = active == null ? "" : active.getId();
        boolean activeIsCustom = !activeId.isEmpty();
        int mode = ThemeStore.selection().mode;

        rows.add(new Row(Row.KIND_FOLLOW, "", "跟随系统", null,
                joinMarkers(false, false, !activeIsCustom && mode == 0)));
        // 内置浅色/深色与用户主题同一套标记口径:
        // 「…默认」= 该类型(亮/暗)的默认主题还是它;「使用中」= 它就是当前生效的那个
        rows.add(new Row(Row.KIND_LIGHT, "", ThemeStore.NAME_BRIGHT, ThemeType.BRIGHT,
                joinMarkers(brightDefault.isEmpty(), false, !activeIsCustom && mode == 1)));
        rows.add(new Row(Row.KIND_DARK, "", ThemeStore.NAME_DARK, ThemeType.DARK,
                joinMarkers(darkDefault.isEmpty(), true, !activeIsCustom && mode == 2)));

        for (ThemeDef def : ThemeStore.userThemes()) {
            if (pendingDeletes.contains(def.getId())) continue; // 草稿里已删:列表立刻消失,落盘等到关闭弹窗
            boolean dark = def.getType().isDark();
            boolean isDefault = def.getId().equals(dark ? darkDefault : brightDefault);
            boolean inUse = def.getId().equals(activeId);
            rows.add(new Row(Row.KIND_USER, def.getId(), def.getName(), def.getType(),
                    joinMarkers(isDefault, dark, inUse)));
        }

        // 勾选位置:选了自定义主题就是它;否则按模式(跟随系统/浅色/深色)
        if (customValid) {
            selectedRow = indexOfUserTheme(draft.customId);
        } else {
            selectedRow = draft.mode == 2 ? 2 : (draft.mode == 1 ? 1 : 0);
        }
        if (selectedRow < 0 || selectedRow >= rows.size()) selectedRow = 0;
    }

    /**
     * 行尾标记拼串:「亮色默认」/「暗色默认」(按该行主题的亮暗)与「使用中」可叠加,
     * 叠加时用 " / " 分隔。
     */
    private static String joinMarkers(boolean isDefault, boolean dark, boolean inUse) {
        StringBuilder sb = new StringBuilder();
        if (isDefault) sb.append(dark ? "暗色默认" : "亮色默认");
        if (inUse) {
            if (sb.length() > 0) sb.append(" / ");
            sb.append("使用中");
        }
        return sb.toString();
    }

    private int indexOfUserTheme(String themeId) {
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (r.kind == Row.KIND_USER && r.themeId.equals(themeId)) return i;
        }
        return -1;
    }

    private void onRowClicked(Row row, int pos) {
        if (row == null) return;
        selectionTouched = true;
        selectedRow = pos;
        // 点浅色/深色 = 强制内置那套(mode 1/2 不看"该类型默认主题");点用户主题才是选中自定义
        switch (row.kind) {
            case Row.KIND_FOLLOW:
                draft.mode = 0;
                draft.customId = "";
                break;
            case Row.KIND_LIGHT:
                draft.mode = 1;
                draft.customId = "";
                break;
            case Row.KIND_DARK:
                draft.mode = 2;
                draft.customId = "";
                break;
            default:
                draft.customId = row.themeId;
                break;
        }
    }

    /** 重排列表(改了数据/选中项后统一走这里) */
    private void reload() {
        rebuildRows();
        refreshList(rows, selectedRow);
    }

    /**
     * 重新读一遍主题列表(编辑页返回后调用):新建/改名/删除过的主题会立刻反映到列表上,
     * 位置按创建顺序(新主题排在固定项之后)。
     */
    public void refreshList() {
        try {
            ThemeStore.reload();
            // 编辑页那边是"保存即选中"(直接落库):把草稿对齐到落盘状态,
            // 否则关闭弹窗时会用打开时那份旧草稿,把这次选择又覆盖掉(用户看到的就是"改了没效果")
            ThemeStore.Selection persisted = ThemeStore.selection();
            if (!draft.sameAs(persisted)) {
                draft = persisted;
                selectionTouched = false;
                defaultTouched = false;
            }
            reload();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // 交互:长按气泡
    // ------------------------------------------------------------------

    /**
     * 长按气泡:编辑 / 设为默认 / 删除(删除走危险文字色,与列表工具条的「删除」同色)。
     * <p>「默认」只影响<b>「跟随系统」</b>模式(见 {@link ThemeStore#resolveActive()}):
     * 用户显式选浅色/深色时永远是内置那套,不受这里改动的影响。
     */
    private void showItemBubble(View anchor, Row row) {
        final ThemeDef def = ThemeStore.find(row.themeId);
        if (def == null) return;
        boolean alreadyDefault = def.getId().equals(
                def.getType().isDark() ? draft.defaultDarkId : draft.defaultBrightId);
        String[] titles = alreadyDefault
                ? new String[]{"编辑", "取消默认", "删除"}
                : new String[]{"编辑", "设为默认", "删除"};
        int[] kinds = new int[]{
                AttachActionDialog.NORMAL,
                AttachActionDialog.NORMAL,
                AttachActionDialog.DANGER};
        AttachActionDialog.show(anchor, titles, kinds, position -> {
            if (position == 0) {
                if (listener != null) listener.onEditTheme(def);
            } else if (position == 1) {
                setDefaultFor(def, !alreadyDefault);
            } else {
                confirmDelete(def);
            }
        });
    }

    /**
     * 长按内置浅色/深色的气泡:该类型的「默认」(供「跟随系统」用)被自定义主题占走时,
     * 允许在这里抢回来;内置本来就是该类型默认时没有可操作项,给一句提示。
     * <p>这与"选中内置"是两件事:点一下浅色/深色就直接生效内置配色(不看默认),
     * 长按「设为默认」改的是<b>跟随系统时</b>该类型用哪套。
     */
    private boolean showBuiltinBubble(View anchor, Row row) {
        boolean dark = row.kind == Row.KIND_DARK;
        String name = dark ? ThemeStore.NAME_DARK : ThemeStore.NAME_BRIGHT;
        if ((dark ? draft.defaultDarkId : draft.defaultBrightId).isEmpty()) {
            AppBubble.toast(name + "已经是该类型的默认");
            return true;
        }
        AttachActionDialog.show(anchor, new String[]{"设为默认"},
                new int[]{AttachActionDialog.NORMAL}, position -> {
                    defaultTouched = true;
                    if (dark) {
                        draft.defaultDarkId = "";
                    } else {
                        draft.defaultBrightId = "";
                    }
                    reload();
                });
        return true;
    }

    /** 设为/取消默认(只影响「跟随系统」):某类型有且只有一份(设置即覆盖) */
    private void setDefaultFor(ThemeDef def, boolean asDefault) {
        defaultTouched = true;
        if (def.getType().isDark()) {
            draft.defaultDarkId = asDefault ? def.getId() : "";
        } else {
            draft.defaultBrightId = asDefault ? def.getId() : "";
        }
        reload();
    }

    private void confirmDelete(ThemeDef def) {
        ConfirmDialog.showDanger(getContext(), "删除主题",
                "确定删除「" + def.getName() + "」吗?它带的背景图(若没有别的主题在用)也会一起清理。",
                "删除", () -> {
                    pendingDeletes.add(def.getId());
                    // 删的是当前选中的:选中项当场切回同类型的内置浅色/深色(落盘仍然等到关闭弹窗)
                    if (def.getId().equals(draft.customId)) {
                        draft.customId = "";
                        draft.mode = def.getType().isDark() ? 2 : 1;
                    }
                    // 它当过的默认主题一并取消
                    if (def.getId().equals(def.getType().isDark() ? draft.defaultDarkId : draft.defaultBrightId)) {
                        defaultTouched = true;
                        if (def.getType().isDark()) {
                            draft.defaultDarkId = "";
                        } else {
                            draft.defaultBrightId = "";
                        }
                    }
                    reload();
                });
    }

    // ------------------------------------------------------------------
    // 提交
    // ------------------------------------------------------------------

    /** 用户在列表里是否改过东西(选中主题 / 设为默认 / 删除) */
    public boolean isDirty() {
        return !pendingDeletes.isEmpty() || selectionTouched || defaultTouched;
    }

    /**
     * 关闭弹窗时一次性提交(磁盘活,但只有几个小文件,弹窗关闭的时机足够快)。
     *
     * <p><b>为什么不能直接把草稿整份覆盖回去</b>:编辑页(另一个 Activity)可能在这期间直接改了落盘状态 ——
     * 最典型的是"删掉了正在用的主题":落盘时会把选中项切回该类型的浅色/深色。
     * 若此时把弹窗打开时那份旧草稿原样写回,就会把这次修正覆盖掉(用户会看到"删了暗色主题却落到浅色")。
     * 所以这里<b>以落盘状态为底</b>,只把"用户在列表里真的点过的那些字段"盖上去。
     *
     * @return 是否需要重启应用生效 —— 依据是"生效配色指纹"变了没:
     *         删掉一个没在用的主题、新建一个还没选中的主题都不该白重启一次;
     *         背景图变更由 {@link ThemeStore#applyActiveBackground()} 立刻生效(各页 onResume 重挂背景层),也不需要重启。
     */
    public boolean commitIfDirty() {
        // 背景是"立刻生效"的:不管有没有列表内改动都同步一次(幂等,且能覆盖"只在编辑页换了背景图")
        ThemeStore.applyActiveBackground();
        if (!isDirty()) return false;

        for (String id : new ArrayList<>(pendingDeletes)) {
            ThemeStore.delete(id);
        }
        // 以落盘状态为底,只覆盖用户真的点过的字段(见方法注释)
        ThemeStore.Selection persisted = ThemeStore.selection();
        if (!selectionTouched) {
            draft.mode = persisted.mode;
            draft.customId = persisted.customId;
        }
        if (!defaultTouched) {
            draft.defaultBrightId = persisted.defaultBrightId;
            draft.defaultDarkId = persisted.defaultDarkId;
        }
        if (!draft.sameAs(ThemeStore.selection())) {
            draft.commit();
        }
        // 提交完可能又换了生效主题(比如删的正是当前用的那个),背景再同步一次
        ThemeStore.applyActiveBackground();
        return !openFingerprint.equals(ThemeStore.activePaletteFingerprint());
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
