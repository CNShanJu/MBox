package com.github.tvbox.osc.ui.activity;

import android.animation.ValueAnimator;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.blankj.utilcode.util.ScreenUtils;
import com.github.tvbox.osc.base.BaseVbActivity;
import com.github.tvbox.osc.config.SystemConfig;
import com.github.tvbox.osc.databinding.ActivityBackgroundSettingBinding;
import com.github.tvbox.osc.storage.theme.ThemeStore;
import com.github.tvbox.osc.ui.kit.BackgroundTuneView;
import com.github.tvbox.osc.ui.kit.PageBackgroundView;
import com.github.tvbox.osc.util.AppBubble;
import com.github.tvbox.osc.util.BgImageImporter;
import com.github.tvbox.osc.util.BgImageTransform;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HeavyTaskUtil;
import com.github.tvbox.osc.util.PageBackgroundStore;
import com.google.android.material.slider.Slider;

/**
 * 背景图设置(二级页,入口:设置 - 主题颜色 - 设置背景图)。
 * <p>
 * 本页自身就是预览:页面背景层({@link PageBackgroundView})显示的就是<b>正在调整的草稿</b>,
 * 手指直接在背景上单指拖动位置、双指等比缩放 —— 跟其他页面用的是同一套渲染,所见即所得。
 * <p>
 * <b>改动只在点"确认背景"时写入配置</b>(不再边拖边生效),直接返回则整份草稿丢弃、其他页面不受影响。
 * <p>
 * 底部控制面板是抽屉:<b>默认展开</b>(调整项一眼可见),点手柄可收起来整屏看背景。
 * <b>高度不写死</b>:面板放在"标题栏以下那块空间"的容器里(贴底 + wrap_content),面板、内容区、滚动区全是
 * wrap_content —— 内容高过可用空间时,只有中间那块滚动区被布局逐层 AT_MOST 压住、内部滚动,
 * 最后一行(背景遮罩)滚一下就能看全,不会被面板下沿裁掉;旋转/分屏/大字体也跟着自适应。
 * 底部"恢复默认/确认背景"两个按钮常显在面板最下面,不会被内容顶出去裁掉。
 * <ul>
 *   <li><b>更换图片</b>:标题栏右侧;系统选图(SAF,免存储权限)→ 校验(是图片、非 GIF、≤30MB)→
 *       纠正 EXIF 方向 → 长边限制 2560 → 转成 WebP(照片有损高质量、带透明通道无损);</li>
 *   <li><b>预设</b>:全屏铺满(等比盖满屏幕、超出裁掉)/ 适应屏幕(按屏幕算,宽或高其中一边
 *       刚好铺满、另一边留边,整图可见)/ 原图大小(原始像素)/
 *       居中·四角(位置);<b>尺寸与位置各自独立</b>:点位置键只挪位置、<b>不动已经调好的大小</b>
 *       (用户口径"设置好大小的一点设置位置又变大了"),当前状态对应的项会高亮。
 *       位置存的是<b>锚点比例</b>(0=起始边贴边/0.5=居中/1=结束边贴边),与屏幕尺寸无关 ——
 *       竖屏摆好的图转到横屏仍是同一观感,不会自己往中间跑(见 util/BgImageTransform);</li>
 *   <li><b>背景图透明度</b>:背景图自身的不透明度(100% = 原图最清楚,0% = 完全看不见);</li>
 *   <li><b>背景遮罩</b>:只开关,不透明度固定({@link SystemConfig#PAGE_BG_SCRIM_DIM}%);关掉后原图直出,
 *       压在底图上的文字对比度会下降;</li>
 *   <li><b>恢复默认</b>:草稿恢复成内置图 + 全屏铺满 + 居中 + 图不透明度 100% + 遮罩开(同样要确认才生效)。</li>
 * </ul>
 */
public class BackgroundSettingActivity extends BaseVbActivity<ActivityBackgroundSettingBinding> {

    /** 系统选图请求码 */
    private static final int REQ_PICK_IMAGE = 0x0B01;

    // ------------------------------------------------------------------
    // 主题模式(入口:主题编辑页的「选择/调整图片」)
    // 同一个页面、同一套预览与手势,区别只在"改的是谁":普通模式改全局底图(SystemConfig),
    // 主题模式改的是某个主题自己的背景(带摆放:缩放/位置/不透明度/遮罩),
    // 确认时把结果经 Intent 交回主题编辑页的草稿(不直接落盘,落盘仍由编辑页的「保存」负责)。
    // ------------------------------------------------------------------
    /** true = 主题模式 */
    public static final String EXTRA_THEME_MODE = "theme_bg_mode";
    /** 主题类型(亮/暗):纯色预览与"恢复默认"要按它取内置值 */
    public static final String EXTRA_THEME_DARK = "theme_bg_dark";
    /** 进入时的图片绝对路径(空=当前不是图片背景) */
    public static final String EXTRA_IN_PATH = "theme_bg_in_path";
    /** 进入时的图片 ref(主题图库相对路径):只调摆放、不换图时原样带回,免得"确认"后把图弄丢 */
    public static final String EXTRA_IN_REF = "theme_bg_in_ref";
    public static final String EXTRA_IN_ZOOM = "theme_bg_in_zoom";
    public static final String EXTRA_IN_ANCHOR_X = "theme_bg_in_anchor_x";
    public static final String EXTRA_IN_ANCHOR_Y = "theme_bg_in_anchor_y";
    public static final String EXTRA_IN_ALPHA = "theme_bg_in_alpha";
    public static final String EXTRA_IN_SCRIM = "theme_bg_in_scrim";
    /** 确认后的结果:是否用图片 + 图片在主题图库里的 ref(相对路径) + 摆放 */
    public static final String EXTRA_OUT_IS_IMAGE = "theme_bg_out_is_image";
    public static final String EXTRA_OUT_REF = "theme_bg_out_ref";
    public static final String EXTRA_OUT_ZOOM = "theme_bg_out_zoom";
    public static final String EXTRA_OUT_ANCHOR_X = "theme_bg_out_anchor_x";
    public static final String EXTRA_OUT_ANCHOR_Y = "theme_bg_out_anchor_y";
    public static final String EXTRA_OUT_ALPHA = "theme_bg_out_alpha";
    public static final String EXTRA_OUT_SCRIM = "theme_bg_out_scrim";

    /** 抽屉展开/收起动画时长(ms) */
    private static final long PANEL_ANIM_MS = 200L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 抽屉是否展开(默认展开:进来就看得到预设/透明度/遮罩;点手柄可收起来整屏看背景) */
    private boolean panelExpanded = true;
    private ValueAnimator panelAnimator;

    /** 编辑草稿:确认前不落配置 */
    private String draftPath = "";
    /** 草稿是否算"用户显式设置"(false=跟随主题默认背景,确认时不写用户设置) */
    private boolean draftUserSet = false;
    /** 主题模式:true 时改的是某个主题自己的背景(结果经 Intent 交回编辑页草稿) */
    private boolean themeMode = false;
    /** 主题模式下的图片 ref(主题图库里的相对路径);选图后才有 */
    private String draftRef = "";
    /** 草稿是否被用户动过(主题模式下"返回=确认"只对动过的草稿生效,见 onBackPressed) */
    private boolean draftTouched = false;
    private float draftZoom = 0f;
    /** 草稿位置:锚点比例 0~1(0=贴左/上、0.5=居中、1=贴右/下;与屏幕尺寸无关,横竖屏同一观感) */
    private float draftAnchorX = BgImageTransform.ANCHOR_CENTER;
    private float draftAnchorY = BgImageTransform.ANCHOR_CENTER;
    /** 草稿里的位置是不是旧版"中心位移"(老配置):拿到图片尺寸后由背景层换算,本页再取回来 */
    private boolean draftLegacyOffsets = false;
    private int draftAlpha = SystemConfig.PAGE_BG_ALPHA_DEFAULT;
    private boolean draftScrim = true;

    /** 背景手势:实时改草稿预览,手势结束只更新草稿(不落配置) */
    private final BackgroundTuneView.Callback tuneCallback = new BackgroundTuneView.Callback() {
        @Override
        public BackgroundTuneView.State onTuneBegin() {
            PageBackgroundView layer = PageBackgroundView.find(BackgroundSettingActivity.this);
            // 图片没加载完 / 旧版位移还没换算成锚点:先不给拖(拿位移当锚点会跳变)
            if (layer == null || layer.getImageWidth() <= 0 || !layer.isPositionResolved()) return null;
            return new BackgroundTuneView.State(
                    layer.getImageWidth(), layer.getImageHeight(),
                    layer.getEffectiveZoom(), layer.getAnchorX(), layer.getAnchorY());
        }

        @Override
        public void onTune(float zoom, float anchorX, float anchorY) {
            PageBackgroundView layer = PageBackgroundView.find(BackgroundSettingActivity.this);
            if (layer != null) layer.setTransform(zoom, anchorX, anchorY);
        }

        @Override
        public void onTuneCommitted(float zoom, float anchorX, float anchorY) {
            draftTouched = true;
            draftZoom = zoom;
            draftAnchorX = anchorX;
            draftAnchorY = anchorY;
            draftLegacyOffsets = false;
            syncPresetChips();
        }
    };

    @Override
    protected void init() {
        themeMode = getIntent() != null && getIntent().getBooleanExtra(EXTRA_THEME_MODE, false);
        mBinding.tune.setCallback(tuneCallback);
        loadDraftFromConfig();
        initPanel();
        initPresets();
        initAlphaSlider();
        initScrimSwitch();
        initButtons();
        applyDraft();
        // 标题栏左箭头与系统返回键同一口径(否则箭头会绕过"返回=确认")
        mBinding.titleBar.setOnBackClickListener(v -> onBackPressed());
        if (themeMode) {
            // 主题模式:同一页面、同一套预览与手势,标题点明改的是主题自己的背景。
            // (两个模式的抽屉都是默认展开的,"确认背景"不会藏在手柄后面丢掉改动 ——
            //  这里只是把标题换掉,不再单独展开面板)
            mBinding.titleBar.setTitle("主题背景图");
        }
        // 抽屉默认展开,进页就能看到预设/透明度/遮罩(不必先去发现手柄)
        //AppBubble.toast("单指拖动调整位置,双指等比缩放;调好后点\"确认背景\"");
    }

    /**
     * 主题模式下"返回 = 确认"(所见即所得):主题背景是主题编辑页草稿的一部分,
     * 用户选好图/拖好位置后直接返回时按丢弃处理,等于白选一张图(已踩过) ——
     * 只有确实动过草稿才这么处理,没动过就单纯退出。普通模式仍按原口径(返回=丢弃草稿)。
     */
    @Override
    public void onBackPressed() {
        if (themeMode && draftTouched) {
            confirmDraft();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // BaseActivity.onResume 会按"配置里的背景"重挂一次(比如刚从选图页回来),
        // 这里再套回草稿,保证预览始终是用户正在调的那份
        applyDraft();
    }

    // ── 草稿 ──

    private void loadDraftFromConfig() {
        if (themeMode) {
            // 主题模式:入参就是"主题编辑页那份草稿"的当前值(不是落盘的主题,避免与编辑页草稿打架)
            android.content.Intent in = getIntent();
            draftPath = in == null ? "" : orEmpty(in.getStringExtra(EXTRA_IN_PATH));
            // 带进来的 ref:只拖动/缩放(不换图)时原样带回,确认后方不会"图片丢了"
            draftRef = in == null ? "" : orEmpty(in.getStringExtra(EXTRA_IN_REF));
            draftUserSet = !draftPath.isEmpty();
            draftZoom = in == null ? 0f : in.getFloatExtra(EXTRA_IN_ZOOM, 0f);
            draftAnchorX = in == null ? BgImageTransform.ANCHOR_CENTER : in.getFloatExtra(EXTRA_IN_ANCHOR_X, BgImageTransform.ANCHOR_CENTER);
            draftAnchorY = in == null ? BgImageTransform.ANCHOR_CENTER : in.getFloatExtra(EXTRA_IN_ANCHOR_Y, BgImageTransform.ANCHOR_CENTER);
            draftAlpha = in == null ? SystemConfig.PAGE_BG_ALPHA_DEFAULT : in.getIntExtra(EXTRA_IN_ALPHA, SystemConfig.PAGE_BG_ALPHA_DEFAULT);
            draftScrim = in == null || in.getBooleanExtra(EXTRA_IN_SCRIM, true);
            draftLegacyOffsets = false;
            return;
        }
        // 生效图源 = 用户设置 > 主题默认(见 SystemConfig.getPageBackgroundPath);没显式设过就是"跟随主题"
        draftPath = SystemConfig.getPageBackgroundPath();
        draftUserSet = SystemConfig.isPageBackgroundUserSet();
        draftZoom = SystemConfig.getPageBackgroundZoom();
        // 位置:已经写过锚点的直接用;老配置只有旧版"中心位移",先原样收下、标记出来,
        // 等背景层拿到图片尺寸换算成锚点后再取回(见 onBackgroundReady)
        draftLegacyOffsets = !SystemConfig.isPageBackgroundAnchorSet();
        if (draftLegacyOffsets) {
            draftAnchorX = SystemConfig.getPageBackgroundOffsetX();
            draftAnchorY = SystemConfig.getPageBackgroundOffsetY();
        } else {
            draftAnchorX = SystemConfig.getPageBackgroundAnchorX();
            draftAnchorY = SystemConfig.getPageBackgroundAnchorY();
        }
        draftAlpha = SystemConfig.getPageBackgroundAlpha();
        draftScrim = SystemConfig.isPageBackgroundScrimEnabled();
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 把草稿套到本页背景层(仅预览,不写配置) */
    private void applyDraft() {
        PageBackgroundView.attach(this, new PageBackgroundView.Config(
                draftPath,
                draftScrim ? SystemConfig.PAGE_BG_SCRIM_DIM : 0,
                draftAlpha, draftZoom, draftAnchorX, draftAnchorY, draftLegacyOffsets));
        PageBackgroundView layer = PageBackgroundView.find(this);
        if (layer != null) layer.setOnImageReadyListener(this::onBackgroundReady);
        syncControls();
        syncControlsEnabled();
        syncPresetChips();
    }

    /**
     * 背景图就绪(成功或失败)后:刷新预设高亮;草稿里若还是旧版"中心位移",
     * 把它换回背景层换算好的锚点(当屏观感不变),这样确认时写入的就是新模型的值。
     */
    private void onBackgroundReady() {
        PageBackgroundView layer = PageBackgroundView.find(this);
        if (draftLegacyOffsets && layer != null && layer.isPositionResolved()) {
            draftAnchorX = layer.getAnchorX();
            draftAnchorY = layer.getAnchorY();
            draftLegacyOffsets = false;
        }
        syncPresetChips();
    }

    /**
     * 有没有背景图:没图时"默认=纯色",缩放/位置/透明度/遮罩都无从谈起 ——
     * 这排控件压暗(预设/遮罩保持可点,点了给一句"请添加背景图";滑杆保持禁用),
     * 再配上面那行"点右上角「更换图片」添加背景图",别让用户点了没反应。
     */
    private void syncControlsEnabled() {
        boolean hasImage = draftPath != null && !draftPath.isEmpty();
        float alpha = hasImage ? 1f : 0.4f;
        mBinding.tvHint.setText(hasImage
                ? "单指拖动调整位置,双指等比缩放;小图按原始大小显示"
                : "当前是纯色背景:点右上角「更换图片」添加背景图");
        TextView[] chips = presetChips();
        for (TextView chip : chips) {
            // **不禁用,只压暗**:禁用态的 View 照样吃掉触摸事件、只是不响应点击,
            // 表现成"点了没反应"(用户口径);保持可点,由点击回调给一句"请添加背景图"
            chip.setAlpha(alpha);
        }
        // 滑杆是**连续**控件:没图时保持禁用(按住拖动本来就没有"一下"可提示,
        // 用触摸监听接管反而会吃掉"从这一行起手滚动面板"的手势),它的压暗 + 上面那行提示已足够
        mBinding.sliderAlpha.setEnabled(hasImage);
        mBinding.sliderAlpha.setAlpha(alpha);
        mBinding.llScrim.setAlpha(alpha);
        mBinding.switchScrim.setEnabled(hasImage);
        mBinding.switchScrim.setAlpha(alpha);
    }

    /**
     * 没有背景图时,预设/遮罩这些"点一下要立刻生效"的控件都不该改(纯色下没有意义),
     * 但**必须给一句人话**:禁用态的 View 仍然吃事件、只是不响应点击,直接 setEnabled(false)
     * 就会变成"点了没反应"(用户口径"没有选择图片点击没有反应")。返回 true 表示有图、可以继续。
     */
    private boolean requireBackgroundImage() {
        if (draftPath != null && !draftPath.isEmpty()) return true;
        AppBubble.toast("请添加背景图");
        return false;
    }

    /**
     * 确认:草稿写入配置(其他页面 onResume 自动套用)。
     * 草稿没被改成"用户自己的图"时,<b>清掉用户设置</b>让它继续跟随主题默认
     * (浅/深主题默认是纯色;以后新增内置主题/自定义主题则跟着该主题的默认背景走)。
     */
    private void confirmDraft() {
        if (themeMode) {
            confirmThemeDraft();
            return;
        }
        if (draftUserSet) {
            SystemConfig.setPageBackgroundPath(draftPath);
        } else {
            SystemConfig.clearPageBackgroundUserSet();
        }
        // 草稿若还是旧版位移(极端情况:配置是老值、图又没加载出来,换算没发生),
        // 别把位移当锚点写进去(值域含义不同,会摆错),退回居中
        float anchorX = draftLegacyOffsets ? BgImageTransform.ANCHOR_CENTER : draftAnchorX;
        float anchorY = draftLegacyOffsets ? BgImageTransform.ANCHOR_CENTER : draftAnchorY;
        SystemConfig.setPageBackgroundTransform(draftZoom, anchorX, anchorY);
        SystemConfig.setPageBackgroundAlpha(draftAlpha);
        SystemConfig.setPageBackgroundScrimEnabled(draftScrim);
        AppBubble.toast("背景已保存");
        finish();
    }

    /**
     * 主题模式确认:把草稿(是否用图 / 图的 ref / 摆放)经 Intent 交回主题编辑页。
     * <p>这里<b>不落盘</b>:主题的落盘统一由编辑页的「保存」负责 ——
     * 否则"在背景页确认了、又回编辑页点取消"会留下一个半改的主题。
     */
    private void confirmThemeDraft() {
        android.content.Intent out = new android.content.Intent();
        boolean isImage = draftUserSet && draftPath != null && !draftPath.isEmpty();
        out.putExtra(EXTRA_OUT_IS_IMAGE, isImage);
        out.putExtra(EXTRA_OUT_REF, isImage ? draftRef : "");
        float anchorX = draftLegacyOffsets ? BgImageTransform.ANCHOR_CENTER : draftAnchorX;
        float anchorY = draftLegacyOffsets ? BgImageTransform.ANCHOR_CENTER : draftAnchorY;
        out.putExtra(EXTRA_OUT_ZOOM, draftZoom);
        out.putExtra(EXTRA_OUT_ANCHOR_X, anchorX);
        out.putExtra(EXTRA_OUT_ANCHOR_Y, anchorY);
        out.putExtra(EXTRA_OUT_ALPHA, draftAlpha);
        out.putExtra(EXTRA_OUT_SCRIM, draftScrim);
        setResult(RESULT_OK, out);
        finish();
    }

    private void syncControls() {
        if ((int) mBinding.sliderAlpha.getValue() != draftAlpha) {
            mBinding.sliderAlpha.setValue(draftAlpha);
        }
        updateAlphaLabel(draftAlpha);
        mBinding.switchScrim.setChecked(draftScrim);
    }

    // ── 抽屉面板 ──

    private void initPanel() {
        // 默认展开:调整项一眼可见(收起态要用户先发现手柄才能调大小/位置)
        applyPanelExpanded(true, false);
        mBinding.panelHandle.setOnClickListener(v -> {
            FastClickCheckUtil.check(v);
            applyPanelExpanded(!panelExpanded, true);
        });
    }

    /**
     * 抽屉展开/收起:内容区高度 0↔"内容自然高度"做动画。
     *
     * <p><b>展开态不锁高度</b>:动画一结束就把内容区交回 {@code wrap_content} —— 之后面板/滚动区
     * 的高度全由布局按可用空间自适应(面板放在"标题栏以下那块空间"的容器里,见布局的 panel_container),
     * 旋转、分屏、系统大字体都不会再留一个"当时算出来的"死高度。
     */
    private void applyPanelExpanded(boolean expanded, boolean animate) {
        panelExpanded = expanded;
        mBinding.tvPanelState.setText(expanded ? "收起" : "展开");
        mBinding.ivPanelArrow.animate().rotation(expanded ? 0f : 180f).setDuration(PANEL_ANIM_MS).start();

        final View content = mBinding.panelContent;
        // 先摘掉旧动画再取消:取消会同步回调 onAnimationEnd,别让它把新状态的高度改回去
        ValueAnimator running = panelAnimator;
        panelAnimator = null;
        if (running != null) running.cancel();

        if (!animate) {
            if (expanded) resetContentHeight(content);
            else setContentHeight(content, 0);
            return;
        }
        int full = measureExpandedHeight(content);
        final ValueAnimator animator = ValueAnimator
                .ofInt(expanded ? 0 : Math.max(content.getHeight(), full), expanded ? full : 0)
                .setDuration(PANEL_ANIM_MS);
        animator.addUpdateListener(a -> setContentHeight(content, (int) a.getAnimatedValue()));
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (panelAnimator != animator) return;   // 已被取消/被新动画接管:别动高度
                panelAnimator = null;
                if (panelExpanded) resetContentHeight(content);   // 交回 wrap_content,之后自适应
            }
        });
        animator.start();
        panelAnimator = animator;
    }

    /**
     * 展开态内容区高度:动画需要一个具体数值,这里按"可用空间"量一次。
     * <p>
     * 可用空间就是面板容器(标题栏以下那块,{@code panel_container})的高度减去面板自身的内外边距与手柄;
     * 容器还没布局完(高度 0)就不封顶,直接量内容自然高度 —— 那种情况只会出现在 {@code animate=false} 的
     * 首次展开(结果立刻被交回 wrap_content,不影响最终布局)。
     */
    private int measureExpandedHeight(View content) {
        int cap = mBinding.panelContainer.getHeight();
        if (cap > 0) {
            ViewGroup.MarginLayoutParams panelLp = (ViewGroup.MarginLayoutParams) mBinding.panel.getLayoutParams();
            cap -= mBinding.panel.getPaddingTop() + mBinding.panel.getPaddingBottom()
                    + panelLp.topMargin + panelLp.bottomMargin + handleHeight();
        }
        int widthSpec = View.MeasureSpec.makeMeasureSpec(Math.max(1, contentWidth()), View.MeasureSpec.EXACTLY);
        int heightSpec = cap > 0
                ? View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST)
                : View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        content.measure(widthSpec, heightSpec);
        return content.getMeasuredHeight();
    }

    /** 内容区高度交回 wrap_content:以后面板/滚动区高度全由布局按可用空间自适应 */
    private void resetContentHeight(View content) {
        setContentHeight(content, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void setContentHeight(View content, int height) {
        ViewGroup.LayoutParams lp = content.getLayoutParams();
        if (lp.height != height) {
            lp.height = height;
            content.setLayoutParams(lp);
        }
    }

    /** 抽屉手柄高度(常显,不参与收起/展开) */
    private int handleHeight() {
        int measured = mBinding.panelHandle.getHeight();
        return measured > 0 ? measured : dp(44);
    }

    /** 面板内容区可用宽度:优先用面板实测宽度,还没布局完就按"屏宽 - 面板外边距 - 面板内边距"推算 */
    private int contentWidth() {
        int panelWidth = mBinding.panel.getWidth();
        if (panelWidth <= 0) {
            int rootWidth = mBinding.getRoot().getWidth();
            if (rootWidth <= 0) rootWidth = ScreenUtils.getScreenWidth();
            ViewGroup.MarginLayoutParams panelLp = (ViewGroup.MarginLayoutParams) mBinding.panel.getLayoutParams();
            panelWidth = rootWidth - panelLp.leftMargin - panelLp.rightMargin;
        }
        return Math.max(1, panelWidth - mBinding.panel.getPaddingLeft() - mBinding.panel.getPaddingRight());
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ── 预设(尺寸 + 位置) ──

    private void initPresets() {
        // 全屏铺满(=超出裁剪:等比盖满屏幕,超出裁掉),位置**不变**
        // (尺寸键与位置键各自独立:谁都不许顺手把对方重置掉 —— 用户口径"设置好大小的一点设置位置又变大了")
        mBinding.chipFill.setOnClickListener(v -> {
            FastClickCheckUtil.check(v);
            applyPreset(BgImageTransform.SIZE_FILL, -1);
        });
        // 适应屏幕(按屏幕算:宽或高其中一边刚好铺满、另一边留边,整图可见),位置不变
        mBinding.chipFit.setOnClickListener(v -> {
            FastClickCheckUtil.check(v);
            applyPreset(BgImageTransform.SIZE_FIT, -1);
        });
        // 原图大小(原始像素),位置不变
        mBinding.chipNative.setOnClickListener(v -> {
            FastClickCheckUtil.check(v);
            applyPreset(BgImageTransform.SIZE_NATIVE, -1);
        });
        // 居中显示(尺寸不变)
        mBinding.chipCenter.setOnClickListener(v -> {
            FastClickCheckUtil.check(v);
            applyPreset(-1, BgImageTransform.POS_CENTER);
        });
        mBinding.chipTl.setOnClickListener(v -> corner(v, BgImageTransform.POS_TOP_LEFT));
        mBinding.chipTr.setOnClickListener(v -> corner(v, BgImageTransform.POS_TOP_RIGHT));
        mBinding.chipBl.setOnClickListener(v -> corner(v, BgImageTransform.POS_BOTTOM_LEFT));
        mBinding.chipBr.setOnClickListener(v -> corner(v, BgImageTransform.POS_BOTTOM_RIGHT));
    }

    /**
     * 位置默认:居中 / 四角。<b>保持当前大小</b>(只挪位置)—— 用户口径"设置好大小的一点设置位置又变大了":
     * 位置与尺寸是两件事,点位置键不该把已经调好的缩放重置成原图大小。
     * 锚点与倍率无关,所以"左上"在任意缩放下都是"图左上角贴屏幕左上角"(超出时对齐该角裁切)。
     */
    private void corner(View chip, int position) {
        FastClickCheckUtil.check(chip);
        applyPreset(-1, position);
    }

    /**
     * 套用预设到草稿并立即预览。
     *
     * @param size     {@link BgImageTransform#SIZE_FILL}/{@link BgImageTransform#SIZE_FIT}/
     *                 {@link BgImageTransform#SIZE_NATIVE};-1=不改尺寸
     * @param position 位置预设({@link BgImageTransform#POS_CENTER} 等);-1=不改位置
     */
    private void applyPreset(int size, int position) {
        if (!requireBackgroundImage()) return;   // 没选图:提示"请添加背景图",不做任何改动
        PageBackgroundView layer = PageBackgroundView.find(this);
        if (layer == null || layer.getImageWidth() <= 0 || layer.getHeight() <= 0) return;
        int imgW = layer.getImageWidth();
        int imgH = layer.getImageHeight();
        int viewW = layer.getWidth();
        int viewH = layer.getHeight();

        float zoom = size < 0 ? layer.getEffectiveZoom()
                : BgImageTransform.zoomForSize(size, imgW, imgH, viewW, viewH);
        float ax;
        float ay;
        if (position < 0) {
            ax = layer.getAnchorX();
            ay = layer.getAnchorY();
        } else {
            // 锚点与倍率/屏幕尺寸无关:四角就是 (0|1, 0|1),换横竖屏后仍然贴在那个角上
            float[] anchor = BgImageTransform.anchorsForPosition(position);
            ax = anchor[0];
            ay = anchor[1];
        }
        draftTouched = true;
        draftZoom = zoom;
        draftAnchorX = ax;
        draftAnchorY = ay;
        draftLegacyOffsets = false;
        layer.setTransform(zoom, ax, ay);
        syncPresetChips();
    }

    /** 按当前背景状态刷新预设高亮(尺寸项与位置项各自独立,可能同时亮两个) */
    private void syncPresetChips() {
        // 没有背景图:一律不高亮。以前这排是 setEnabled(false),禁用态会盖过"选中"外观;
        // 现在保持可点(好给"请添加背景图"的提示),就得自己收住高亮 ——
        // 否则纯色下默认锚点是居中,"居中"会亮成选中,看着像已经用上了设置
        if (draftPath == null || draftPath.isEmpty()) {
            for (TextView chip : presetChips()) setChipSelected(chip, false);
            return;
        }
        PageBackgroundView layer = PageBackgroundView.find(this);
        int imgW = layer == null ? 0 : layer.getImageWidth();
        int imgH = layer == null ? 0 : layer.getImageHeight();
        int viewW = layer == null ? 0 : layer.getWidth();
        int viewH = layer == null ? 0 : layer.getHeight();
        float zoom = layer == null ? 1f : layer.getEffectiveZoom();
        // 旧版位移还没换算完时不能当锚点用(值域含义不同),按居中高亮
        boolean anchorsReady = layer != null && layer.isPositionResolved();
        float ax = anchorsReady ? layer.getAnchorX() : BgImageTransform.ANCHOR_CENTER;
        float ay = anchorsReady ? layer.getAnchorY() : BgImageTransform.ANCHOR_CENTER;

        setChipSelected(mBinding.chipFill,
                BgImageTransform.matchesSize(zoom, BgImageTransform.SIZE_FILL, imgW, imgH, viewW, viewH));
        setChipSelected(mBinding.chipFit,
                BgImageTransform.matchesSize(zoom, BgImageTransform.SIZE_FIT, imgW, imgH, viewW, viewH));
        setChipSelected(mBinding.chipNative,
                BgImageTransform.matchesSize(zoom, BgImageTransform.SIZE_NATIVE, imgW, imgH, viewW, viewH));
        setChipSelected(mBinding.chipCenter,
                BgImageTransform.matchesPosition(ax, ay, BgImageTransform.POS_CENTER));
        setChipSelected(mBinding.chipTl,
                BgImageTransform.matchesPosition(ax, ay, BgImageTransform.POS_TOP_LEFT));
        setChipSelected(mBinding.chipTr,
                BgImageTransform.matchesPosition(ax, ay, BgImageTransform.POS_TOP_RIGHT));
        setChipSelected(mBinding.chipBl,
                BgImageTransform.matchesPosition(ax, ay, BgImageTransform.POS_BOTTOM_LEFT));
        setChipSelected(mBinding.chipBr,
                BgImageTransform.matchesPosition(ax, ay, BgImageTransform.POS_BOTTOM_RIGHT));
    }

    private void setChipSelected(TextView chip, boolean selected) {
        if (chip.isSelected() != selected) chip.setSelected(selected);
    }

    /** 8 个预设键(尺寸 3 + 居中/四角 5):压暗、清高亮都整组处理,别再各写一份清单 */
    private TextView[] presetChips() {
        return new TextView[]{mBinding.chipFill, mBinding.chipFit, mBinding.chipNative, mBinding.chipCenter,
                mBinding.chipTl, mBinding.chipTr, mBinding.chipBl, mBinding.chipBr};
    }

    // ── 透明度 / 遮罩 ──

    /** 背景图透明度滑杆:直接就是背景图自身的不透明度(100=原图) */
    private void initAlphaSlider() {
        Slider slider = mBinding.sliderAlpha;
        slider.setValueFrom(0f);
        slider.setValueTo(100f);
        slider.setStepSize(1f);
        slider.setValue(draftAlpha);
        updateAlphaLabel(draftAlpha);
        slider.addOnChangeListener((s, value, fromUser) -> {
            draftTouched = true;
            draftAlpha = (int) value;
            PageBackgroundView layer = PageBackgroundView.find(this);
            if (layer != null) layer.setImageAlpha(draftAlpha);
            updateAlphaLabel(draftAlpha);
        });
    }

    /** 遮罩开关:不透明度固定,只有开/关 */
    private void initScrimSwitch() {
        mBinding.switchScrim.setChecked(draftScrim);
        mBinding.llScrim.setOnClickListener(v -> {
            FastClickCheckUtil.check(v);
            if (!requireBackgroundImage()) return;   // 没选图:提示"请添加背景图"
            draftTouched = true;
            draftScrim = !draftScrim;
            mBinding.switchScrim.setChecked(draftScrim);
            PageBackgroundView layer = PageBackgroundView.find(this);
            if (layer != null) layer.setDim(draftScrim ? SystemConfig.PAGE_BG_SCRIM_DIM : 0);
        });
    }

    private void initButtons() {
        // 更换图片:标题栏右侧
        mBinding.tvPick.setOnClickListener(v -> {
            FastClickCheckUtil.check(v);
            pickImage();
        });
        mBinding.btnReset.setOnClickListener(v -> {
            FastClickCheckUtil.check(v);
            // 草稿恢复成"跟随主题默认背景"(浅/深主题即纯色) + 位置/透明度/遮罩回默认;确认后才真正生效
            draftTouched = true;
            draftUserSet = false;
            draftRef = "";
            // 主题模式下"默认"就是该主题类型的纯色(取内置亮/暗的页面底色),普通模式仍是跟随主题默认背景
            draftPath = "";
            draftZoom = 0f;
            draftAnchorX = BgImageTransform.ANCHOR_CENTER;
            draftAnchorY = BgImageTransform.ANCHOR_CENTER;
            draftLegacyOffsets = false;
            draftAlpha = SystemConfig.PAGE_BG_ALPHA_DEFAULT;
            draftScrim = true;
            applyDraft();
        });
        mBinding.btnConfirm.setOnClickListener(v -> {
            FastClickCheckUtil.check(v);
            confirmDraft();
        });
    }

    private void updateAlphaLabel(int percent) {
        mBinding.tvAlpha.setText(percent + "%");
    }

    // ── 选图 / 导入 ──

    /** 系统选图:SAF(ACTION_OPEN_DOCUMENT),不需要存储权限 */
    private void pickImage() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            startActivityForResult(intent, REQ_PICK_IMAGE);
        } catch (Throwable th) {
            AppBubble.toast("没有可用的图片选择器");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_IMAGE || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        // 校验/转码是磁盘与解码重活:放共享执行器后台做,期间给个加载提示
        showLoadingDialog("正在导入图片…");
        HeavyTaskUtil.getBigTaskExecutorService().execute(() -> {
            if (themeMode) {
                // 主题模式:与"设置背景图"页同一套导入管道(限大小/纠 EXIF/转 WebP/存储预检),
                // 差别只在收编位置 —— 进主题图库(按内容 hash 去重,多个主题用同一张图只存一份)
                BgImageImporter.Result imported = BgImageImporter.toWebp(getApplicationContext(), uri);
                String ref = imported.ok() ? ThemeStore.registerBackground(imported.webp) : "";
                final String error = imported.ok()
                        ? (ref.isEmpty() ? "图片保存失败,换一张试试" : null)
                        : (imported.error == null ? "图片导入失败" : imported.error);
                mainHandler.post(() -> {
                    dismissLoadingDialog();
                    if (isFinishing()) return;
                    if (error != null) {
                        AppBubble.toast(error);
                        return;
                    }
                    draftPath = ThemeStore.resolveBackgroundPath(ref);
                    draftRef = ref;
                    draftUserSet = true;
                    draftTouched = true;
                    draftZoom = 0f;
                    draftAnchorX = BgImageTransform.ANCHOR_CENTER;
                    draftAnchorY = BgImageTransform.ANCHOR_CENTER;
                    draftLegacyOffsets = false;
                    applyDraft();
                    AppBubble.toast("图片已更换，调整后点“确认背景”");
                });
                return;
            }
            final PageBackgroundStore.ImportResult result =
                    PageBackgroundStore.importFromUri(getApplicationContext(), uri);
            mainHandler.post(() -> {
                dismissLoadingDialog();
                if (isFinishing()) return;
                if (!result.ok()) {
                    AppBubble.toast(result.error == null ? "导入失败，请换张图" : result.error);
                    return;
                }
                // 换新图进草稿:位置居中、缩放回到"自动"(普通图铺满屏幕,很小的图按原始像素显示)
                draftPath = result.path;
                draftUserSet = true;
                draftTouched = true;
                draftZoom = 0f;
                draftAnchorX = BgImageTransform.ANCHOR_CENTER;
                draftAnchorY = BgImageTransform.ANCHOR_CENTER;
                draftLegacyOffsets = false;
                applyDraft();
                AppBubble.toast("图片已更换，调整后点“确认背景”");
            });
        });
    }

    @Override
    protected void onDestroy() {
        ValueAnimator running = panelAnimator;
        panelAnimator = null;
        if (running != null) running.cancel();
        super.onDestroy();
    }
}
