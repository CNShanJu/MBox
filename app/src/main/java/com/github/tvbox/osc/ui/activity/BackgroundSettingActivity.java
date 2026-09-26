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
import com.github.tvbox.osc.ui.kit.BackgroundTuneView;
import com.github.tvbox.osc.ui.kit.PageBackgroundView;
import com.github.tvbox.osc.util.AppBubble;
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
 * 底部控制面板是抽屉:默认收起(只留一条"背景图设置"手柄,整屏看背景),点手柄展开/收起。
 * <b>抽屉高度自适应</b>:内容区高度按"标题栏以下、屏幕以内"的可用高度封顶,超出部分由中间那块
 * 内容区内部滚动消化,底部"恢复默认/确认背景"两个按钮常显在面板最下面,不会被内容顶出去裁掉。
 * <ul>
 *   <li><b>更换图片</b>:标题栏右侧;系统选图(SAF,免存储权限)→ 校验(是图片、非 GIF、≤30MB)→
 *       纠正 EXIF 方向 → 长边限制 2560 → 转成 WebP(照片有损高质量、带透明通道无损);</li>
 *   <li><b>预设</b>:全屏铺满(等比盖满屏幕、超出裁掉)/ 适应屏幕(按屏幕算,宽或高其中一边
 *       刚好铺满、另一边留边,整图可见)/ 原图大小(原始像素)/ 居中显示 /
 *       四角(原图大小并把边贴到该角);尺寸与位置各自独立,当前状态对应的项会高亮。
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

    /** 抽屉展开/收起动画时长(ms) */
    private static final long PANEL_ANIM_MS = 200L;

    /** 面板顶部与标题栏之间至少留的空隙(dp):展开到最高也不盖住"更换图片" */
    private static final int PANEL_TOP_GAP_DP = 10;
    /** 内容区最小可用高度(dp):极端小屏也别把设置项压没 */
    private static final int PANEL_MIN_CONTENT_DP = 120;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 抽屉是否展开(默认收起:整屏看背景) */
    private boolean panelExpanded = false;
    private ValueAnimator panelAnimator;
    /** 内容区展开后的高度缓存(键=面板宽度 + 可用高度上限,任一变化就重量) */
    private int panelContentFullHeight;
    private int panelContentFullWidth = -1;
    private int panelContentFullCap = -1;
    /** 底部按钮行(常显)高度:滚动区高度 = 内容区高度 - 它 */
    private int panelActionsHeight;
    /** 上次量到的根布局高度:旋转/分屏后可用高度变了,要按新高度重新封顶 */
    private int lastRootHeight;

    /** 编辑草稿:确认前不落配置 */
    private String draftPath = "";
    /** 草稿是否算"用户显式设置"(false=跟随主题默认背景,确认时不写用户设置) */
    private boolean draftUserSet = false;
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
            draftZoom = zoom;
            draftAnchorX = anchorX;
            draftAnchorY = anchorY;
            draftLegacyOffsets = false;
            syncPresetChips();
        }
    };

    @Override
    protected void init() {
        mBinding.tune.setCallback(tuneCallback);
        loadDraftFromConfig();
        initPanel();
        initPresets();
        initAlphaSlider();
        initScrimSwitch();
        initButtons();
        applyDraft();
        // 面板默认收起,进页先提示一句怎么调(不然不知道能直接拖背景)
        //AppBubble.toast("单指拖动调整位置,双指等比缩放;调好后点\"确认背景\"");
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
     * 这排控件置灰并提示去换图,避免点了没反应。
     */
    private void syncControlsEnabled() {
        boolean hasImage = draftPath != null && !draftPath.isEmpty();
        float alpha = hasImage ? 1f : 0.4f;
        mBinding.tvHint.setText(hasImage
                ? "单指拖动调整位置,双指等比缩放;小图按原始大小显示"
                : "当前是纯色背景:点右上角「更换图片」选一张图");
        TextView[] chips = {mBinding.chipFill, mBinding.chipFit, mBinding.chipNative, mBinding.chipCenter,
                mBinding.chipTl, mBinding.chipTr, mBinding.chipBl, mBinding.chipBr};
        for (TextView chip : chips) {
            chip.setEnabled(hasImage);
            chip.setAlpha(alpha);
        }
        mBinding.sliderAlpha.setEnabled(hasImage);
        mBinding.sliderAlpha.setAlpha(alpha);
        mBinding.llScrim.setEnabled(hasImage);
        mBinding.llScrim.setAlpha(alpha);
        mBinding.switchScrim.setEnabled(hasImage);
        mBinding.switchScrim.setAlpha(alpha);
    }

    /**
     * 确认:草稿写入配置(其他页面 onResume 自动套用)。
     * 草稿没被改成"用户自己的图"时,<b>清掉用户设置</b>让它继续跟随主题默认
     * (浅/深主题默认是纯色;以后新增内置主题/自定义主题则跟着该主题的默认背景走)。
     */
    private void confirmDraft() {
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

    private void syncControls() {
        if ((int) mBinding.sliderAlpha.getValue() != draftAlpha) {
            mBinding.sliderAlpha.setValue(draftAlpha);
        }
        updateAlphaLabel(draftAlpha);
        mBinding.switchScrim.setChecked(draftScrim);
    }

    // ── 抽屉面板 ──

    private void initPanel() {
        applyPanelExpanded(false, false);
        mBinding.panelHandle.setOnClickListener(v -> {
            FastClickCheckUtil.check(v);
            applyPanelExpanded(!panelExpanded, true);
        });
        // 旋转/分屏/收起键盘后可用高度变了:作废缓存,展开态就地按新高度重新封顶(不重播动画)
        mBinding.getRoot().addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int rootHeight = bottom - top;
            if (rootHeight == lastRootHeight) return;
            lastRootHeight = rootHeight;
            invalidatePanelMetrics();
            if (panelExpanded) {
                setContentHeight(mBinding.panelContent, measureContentHeight(mBinding.panelContent));
            }
        });
    }

    /** 作废"内容区高度"的缓存(尺寸变化后重量) */
    private void invalidatePanelMetrics() {
        panelContentFullHeight = 0;
        panelContentFullWidth = -1;
        panelContentFullCap = -1;
        panelActionsHeight = 0;
    }

    /** 抽屉展开/收起:内容区高度 0↔实际高度做动画(收起后只剩手柄那一条) */
    private void applyPanelExpanded(boolean expanded, boolean animate) {
        panelExpanded = expanded;
        mBinding.tvPanelState.setText(expanded ? "收起" : "展开");
        mBinding.ivPanelArrow.animate().rotation(expanded ? 0f : 180f).setDuration(PANEL_ANIM_MS).start();

        final View content = mBinding.panelContent;
        int full = measureContentHeight(content);
        if (panelAnimator != null) {
            panelAnimator.cancel();
            panelAnimator = null;
        }
        if (full <= 0) {
            // 量不出高度(极端情况):直接交给 wrap_content / 0,别把内容锁死成 0
            ViewGroup.LayoutParams lp = content.getLayoutParams();
            lp.height = expanded ? ViewGroup.LayoutParams.WRAP_CONTENT : 0;
            content.setLayoutParams(lp);
            return;
        }
        if (!animate) {
            setContentHeight(content, expanded ? full : 0);
            return;
        }
        int from = expanded ? 0 : full;
        int to = expanded ? full : 0;
        ValueAnimator animator = ValueAnimator.ofInt(from, to).setDuration(PANEL_ANIM_MS);
        animator.addUpdateListener(a -> setContentHeight(content, (int) a.getAnimatedValue()));
        animator.start();
        panelAnimator = animator;
    }

    /**
     * 展开后内容区应有的高度 = min(自然高度, 可用高度上限)。
     * <p>
     * <b>必须封顶</b>:面板是贴底的 wrap_content,父布局只给它"屏高 - 外边距",内容更高时面板会被
     * 父布局裁掉 —— 表现就是最下面那排按钮"被内容顶出去、显示不全"。封顶后超出部分由内容区
     * 内部滚动消化,而底部按钮行常显在面板最下面(见 {@link #setContentHeight}),不会再被裁。
     */
    private int measureContentHeight(View content) {
        int panelWidth = mBinding.panel.getWidth();
        int cap = maxContentHeight();
        if (panelContentFullHeight > 0 && panelContentFullWidth == panelWidth && panelContentFullCap == cap) {
            return panelContentFullHeight;
        }
        int natural = measureNaturalContentHeight(content, panelWidth);
        int height = (natural > 0 && (cap <= 0 || natural <= cap)) ? natural : cap;
        // 至少留住底部按钮行(内容再挤也不能把按钮挤没)
        height = Math.max(height, fixedBottomHeight());
        if (height > 0) {
            panelContentFullHeight = height;
            panelContentFullWidth = panelWidth;
            panelContentFullCap = cap;
        }
        return height;
    }

    /**
     * 内容区自然高度:先把滚动区放开成 wrap_content 再整体量一次。
     * 量之前必须放开,否则会拿上一次动画的中间高度当自然高度。
     */
    private int measureNaturalContentHeight(View content, int panelWidth) {
        setScrollHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        int width = contentWidth(panelWidth);
        int widthSpec = View.MeasureSpec.makeMeasureSpec(Math.max(1, width), View.MeasureSpec.EXACTLY);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        content.measure(widthSpec, heightSpec);
        return content.getMeasuredHeight();
    }

    /**
     * 内容区可用高度上限:面板整体(手柄 + 内容 + 面板内边距 + 面板外边距)必须落在标题栏之下、
     * 屏幕之内,否则面板会被父布局裁掉。
     */
    private int maxContentHeight() {
        int rootHeight = mBinding.getRoot().getHeight();
        if (rootHeight <= 0) rootHeight = ScreenUtils.getScreenHeight();
        if (rootHeight <= 0) return 0;
        int titleHeight = mBinding.titleBar.getHeight() > 0 ? mBinding.titleBar.getHeight() : dp(45);
        ViewGroup.MarginLayoutParams panelLp = (ViewGroup.MarginLayoutParams) mBinding.panel.getLayoutParams();
        int chrome = titleHeight + dp(PANEL_TOP_GAP_DP) + panelLp.topMargin + panelLp.bottomMargin
                + mBinding.panel.getPaddingTop() + mBinding.panel.getPaddingBottom()
                + handleHeight();
        return Math.max(dp(PANEL_MIN_CONTENT_DP), rootHeight - chrome);
    }

    /** 面板内容区可用宽度:优先用面板实测宽度,还没布局完就按"屏宽 - 面板外边距 - 面板内边距"推算 */
    private int contentWidth(int panelWidth) {
        if (panelWidth > 0) {
            return panelWidth - mBinding.panel.getPaddingLeft() - mBinding.panel.getPaddingRight();
        }
        int rootWidth = mBinding.getRoot().getWidth();
        if (rootWidth <= 0) rootWidth = ScreenUtils.getScreenWidth();
        ViewGroup.MarginLayoutParams panelLp = (ViewGroup.MarginLayoutParams) mBinding.panel.getLayoutParams();
        return rootWidth - panelLp.leftMargin - panelLp.rightMargin
                - mBinding.panel.getPaddingLeft() - mBinding.panel.getPaddingRight();
    }

    /** 抽屉手柄高度(常显,不参与收起/展开) */
    private int handleHeight() {
        int measured = mBinding.panelHandle.getHeight();
        return measured > 0 ? measured : dp(44);
    }

    /** 底部按钮行高度(含其上外边距):它常显,滚动区高度 = 内容区高度 - 它 */
    private int fixedBottomHeight() {
        if (panelActionsHeight > 0) return panelActionsHeight;
        View actions = mBinding.panelActions;
        int widthSpec = View.MeasureSpec.makeMeasureSpec(
                Math.max(1, contentWidth(mBinding.panel.getWidth())), View.MeasureSpec.EXACTLY);
        actions.measure(widthSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int margin = 0;
        if (actions.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            margin = ((ViewGroup.MarginLayoutParams) actions.getLayoutParams()).topMargin;
        }
        panelActionsHeight = actions.getMeasuredHeight() + margin;
        return panelActionsHeight;
    }

    /**
     * 内容区高度 = 可滚动区高度 + 底部按钮行:按钮行固定在内容区最下面,不随内容滚动、
     * 也不会被上面的设置项顶出面板(内容超出时只有上面的滚动区在滚)。
     */
    private void setContentHeight(View content, int height) {
        ViewGroup.LayoutParams lp = content.getLayoutParams();
        if (lp.height != height) {
            lp.height = height;
            content.setLayoutParams(lp);
        }
        setScrollHeight(Math.max(0, height - fixedBottomHeight()));
    }

    private void setScrollHeight(int height) {
        View scroll = mBinding.panelScroll;
        ViewGroup.LayoutParams lp = scroll.getLayoutParams();
        if (lp.height == height) return;
        lp.height = height;
        scroll.setLayoutParams(lp);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ── 预设(尺寸 + 位置) ──

    private void initPresets() {
        // 全屏铺满(=超出裁剪:等比盖满屏幕,超出裁掉)+ 居中
        mBinding.chipFill.setOnClickListener(v -> {
            FastClickCheckUtil.check(v);
            applyPreset(BgImageTransform.SIZE_FILL, BgImageTransform.POS_CENTER);
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

    /** 四角:原图大小 + 把图边贴到该角 */
    private void corner(View chip, int position) {
        FastClickCheckUtil.check(chip);
        applyPreset(BgImageTransform.SIZE_NATIVE, position);
    }

    /**
     * 套用预设到草稿并立即预览。
     *
     * @param size     {@link BgImageTransform#SIZE_FILL}/{@link BgImageTransform#SIZE_FIT}/
     *                 {@link BgImageTransform#SIZE_NATIVE};-1=不改尺寸
     * @param position 位置预设({@link BgImageTransform#POS_CENTER} 等);-1=不改位置
     */
    private void applyPreset(int size, int position) {
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
        draftZoom = zoom;
        draftAnchorX = ax;
        draftAnchorY = ay;
        draftLegacyOffsets = false;
        layer.setTransform(zoom, ax, ay);
        syncPresetChips();
    }

    /** 按当前背景状态刷新预设高亮(尺寸项与位置项各自独立,可能同时亮两个) */
    private void syncPresetChips() {
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
            draftUserSet = false;
            draftPath = SystemConfig.getThemeDefaultBackground();
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
            final PageBackgroundStore.ImportResult result =
                    PageBackgroundStore.importFromUri(getApplicationContext(), uri);
            mainHandler.post(() -> {
                dismissLoadingDialog();
                if (isFinishing()) return;
                if (!result.ok()) {
                    AppBubble.toast(result.error == null ? "图片导入失败,换一张试试" : result.error);
                    return;
                }
                // 换新图进草稿:位置居中、缩放回到"自动"(普通图铺满屏幕,很小的图按原始像素显示)
                draftPath = result.path;
                draftUserSet = true;
                draftZoom = 0f;
                draftAnchorX = BgImageTransform.ANCHOR_CENTER;
                draftAnchorY = BgImageTransform.ANCHOR_CENTER;
                draftLegacyOffsets = false;
                applyDraft();
                AppBubble.toast("已换图,可拖动调整位置;记得点\"确认背景\"");
            });
        });
    }

    @Override
    protected void onDestroy() {
        if (panelAnimator != null) {
            panelAnimator.cancel();
            panelAnimator = null;
        }
        super.onDestroy();
    }
}
