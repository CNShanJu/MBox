package com.github.tvbox.osc.util;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.github.tvbox.osc.R;

/**
 * 全 App 海报占位统一组件(加载态 / 失败态两种形态,只此一处实现):
 * <ul>
 *   <li>{@link #loading} —— 加载态:灰底圆角 + 居中猫图标,无文字(与布局里的 XML 占位
 *       {@code placeholder_poster} 同色同圆角同图标);</li>
 *   <li>{@link #failed} —— 失败/无封面:同上一块底,再叠一行"图片加载失败"。</li>
 * </ul>
 * 色值/圆角/图标与 XML 占位 {@code placeholder_poster} 同源(bg_card + radius_card + ic_placeholder_cat),
 * 布局里的 XML 是"静态形态"(首帧/未走统一加载入口的地方用),本类是"自适应形态"(统一加载入口用),
 * 两态观感一致,只是本类会按宿主尺寸缩放图标。
 *
 * <p><b>为什么图标要自适应</b>:图标位图各密度都是 <b>96dp</b>(几乎撑满画布,四周没有留白)。作为
 * ImageView 的 <b>背景层</b>绘制时按原始 96dp 居中画,宿主比 96dp 小(下载页条目 56×74dp 的封面)
 * 就会**超出并被裁掉**;所以这里统一"只缩不放":宿主宽高都 ≥ 图标原始尺寸时原样 96dp(与 XML 占位
 * 逐像素一致),小于时等比缩到宿主的 90%(永不超出显示范围)。
 *
 * <p><b>占位必须放背景层,不能塞进 src</b>:塞 src 会被 ImageView 的 scaleType(centerCrop)
 * 当成普通图片等比放大再裁剪,小卡片上直接"撑出显示范围"(下载页曾出现的根因)。
 *
 * <p><b>为什么用代码画而非 XML</b>:layer-list / vector 渲染不了中文,且这里要按控件尺寸动态排版。
 *
 * <p><b>失败态底部覆盖层的避让</b>:搜索结果卡、{@code item_grid}(首页/收藏/历史宫格)的海报底部有一条
 * 渐变黑底信息条(集数"更新至N集" + 剧名),它是海报卡的兄弟视图、绘制在图片之上;占位内容压到
 * 那条黑底上会被盖住。故绘制时**自动量出**这条覆盖层的高度(同一父容器里、排在图片之后、底边与
 * 图片齐平的可见兄弟视图),让整组排在它上方;宿主没有这种覆盖层(详情弹窗海报、没有集数的卡)
 * 时不避让,直接居中。
 *
 * <p><b>怎么做到"既居中又不被盖"</b>(仅失败态):整组高度 G,覆盖层高 R,卡片高 H。
 * ① 想真正居中,整组上下各留出 R,即要求 G ≤ H - 2R → 不满足就**等比缩小图标**(留 40dp 下限);
 * ② 缩小仍不够(卡片很扁)就把整组整体上移,最少让到 R 之上(H - R - G);
 * ③ 图标再放不下就只画文字 —— 保证"图片加载失败"在任何卡片形状下都露在信息条上方,
 * 同时尽量让视线重心落在卡片中间。
 *
 * <p>全 App 占位只此一处(AGENTS §七:禁止各页面自造第二套占位图)。
 */
public class PosterPlaceholderDrawable extends Drawable {

    private static final String FAILED_TEXT = "图片加载失败";

    /** 失败态:为了居中而缩图标的尺寸下限(dp):再小就宁可整体上移,也不把图标缩成一个点 */
    private static final float MIN_ICON_DP = 40f;

    /** 加载态:宿主比图标小时,图标等比缩到宿主短边的这个比例(留一点边距,不贴边) */
    private static final float ICON_MAX_FIT = 0.9f;

    private final boolean withText;
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Drawable icon;
    private final float density;
    private final float cornerRadiusPx;
    private final float textSizePx;
    private final float minTextSizePx;
    private final float iconTextGapPx;
    private final float minIconPx;

    // ── 覆盖条避让高度的缓存(见 resolveOverlayReservePx):它是背景层,扫光动画每帧都会重画这里 ──
    /** 缓存是否有效 */
    private boolean overlayReserveValid;
    /** 上次量得的覆盖条高度(px) */
    private float overlayReservePx;
    /** 缓存键:宿主在父容器中的下标(用 getChildAt 复核身份,省掉每帧的 indexOfChild) */
    private int overlayProbeIndex = -1;
    /** 缓存键:宿主自身的几何 */
    private int overlayProbeW, overlayProbeH, overlayProbeTop, overlayProbeBottom;
    /** 缓存键:上次扫过的每个兄弟视图,及其(可见性/高/上/下) */
    private View[] overlayProbeSiblings;
    private int[] overlayProbeStates;

    /** 加载态占位:灰底 + 居中图标(无文字),图标随宿主缩放,永不超出 */
    public static Drawable loading(Context context) {
        return new PosterPlaceholderDrawable(context, false);
    }

    /** 失败/无封面占位:灰底 + 居中图标 + "图片加载失败" */
    public static Drawable failed(Context context) {
        return new PosterPlaceholderDrawable(context, true);
    }

    /** 每个 ImageView 需各自持有实例(Drawable 有 bounds 状态),故按次新建;开销很小 */
    private PosterPlaceholderDrawable(Context context, boolean withText) {
        this.withText = withText;
        density = context.getResources().getDisplayMetrics().density;
        bgPaint.setColor(ContextCompat.getColor(context, R.color.bg_card));
        cornerRadiusPx = context.getResources().getDimension(R.dimen.radius_card);
        icon = ContextCompat.getDrawable(context, R.drawable.ic_placeholder_cat);
        textSizePx = 12f * density;
        minTextSizePx = 9f * density;
        iconTextGapPx = 3f * density;
        minIconPx = MIN_ICON_DP * density;
        textPaint.setColor(ContextCompat.getColor(context, R.color.text_sub_foreground));
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        // 1) 灰底圆角(与加载态占位同色同圆角;ImageView 若自身有更小圆角由控件 clipToOutline 处理)
        float radius = Math.min(cornerRadiusPx, Math.min(b.width(), b.height()) * 0.5f);
        canvas.drawRoundRect(b.left, b.top, b.right, b.bottom, radius, radius, bgPaint);
        if (icon == null) return;

        if (!withText) {
            drawLoadingIcon(canvas, b);
            return;
        }
        drawFailed(canvas, b);
    }

    /** 加载态:图标居中,"只缩不放" —— 宿主够大就用原始 96dp,比图标小就等比缩到短边的 90% */
    private void drawLoadingIcon(Canvas canvas, Rect b) {
        float iw = Math.max(1, icon.getIntrinsicWidth());
        float ih = Math.max(1, icon.getIntrinsicHeight());
        float maxSide = Math.min(b.width(), b.height()) * ICON_MAX_FIT;
        float scale = Math.min(1f, Math.min(maxSide / iw, maxSide / ih));
        float w = iw * scale;
        float h = ih * scale;
        int left = Math.round(b.exactCenterX() - w / 2f);
        int top = Math.round(b.exactCenterY() - h / 2f);
        icon.setBounds(left, top, left + Math.round(w), top + Math.round(h));
        icon.draw(canvas);
    }

    /** 失败态:图标 + 文字整组居中,底部有覆盖条(集数信息条)时让到它上方 */
    private void drawFailed(Canvas canvas, Rect b) {
        // 文字尺寸:随宽度等比并夹取上限,同时保证 6 个字不超出可用宽度
        float size = Math.max(minTextSizePx,
                Math.min(textSizePx, Math.min(b.width() * 0.14f, b.width() / 6.5f)));
        textPaint.setTextSize(size);

        // 底部覆盖层(画在图片之上的信息条)高度:让它,别被盖住;没有覆盖层就是 0(纯居中)
        float reserve = Math.min(resolveOverlayReservePx(), Math.max(0f, b.height() - size));

        // 图标:先原尺寸;为了能"整体居中"按需等比缩小(下限 minIconPx),放不下就只留文字
        float iconH = 0f;
        float iconW = 0f;
        float iw = Math.max(1, icon.getIntrinsicWidth());
        float ih = Math.max(1, icon.getIntrinsicHeight());
        float fitAboveBar = b.height() - reserve - size - iconTextGapPx;   // 硬约束:整组必须排在覆盖层上方
        float fitCentered = b.height() - 2f * reserve - size - iconTextGapPx; // 想真居中:上下各让出覆盖层
        if (fitAboveBar >= minIconPx * 0.5f) {   // 版面太扁(图标只剩一丁点)宁可不画图标,只留文字
            float target = Math.max(minIconPx, fitCentered);
            iconH = Math.min(ih, Math.min(fitAboveBar, target));
            iconW = iw * (iconH / ih);
        }
        // 宿主太窄(小封面/窄卡片)时再按宽度夹一次:图标一样不许超出显示范围
        float maxW = b.width() * ICON_MAX_FIT;
        if (iconW > maxW) {
            iconH *= maxW / iconW;
            iconW = maxW;
        }

        // "图标 + 文字"当成一个整体:优先在整块版面里垂直居中;居中的话会压到覆盖层时再整体上移
        float groupH = iconH + (iconH > 0f ? iconTextGapPx : 0f) + size;
        float groupTop = b.top + (b.height() - groupH) / 2f;
        float limitTop = b.bottom - reserve - groupH;   // 整组底边不越过覆盖层
        if (groupTop > limitTop) groupTop = limitTop;
        if (groupTop < b.top) groupTop = b.top;
        if (iconH > 0f) {
            int left = Math.round(b.exactCenterX() - iconW / 2f);
            int top = Math.round(groupTop);
            icon.setBounds(left, top, left + Math.round(iconW), top + Math.round(iconH));
            icon.draw(canvas);
            groupTop += iconH + iconTextGapPx;
        }
        canvas.drawText(FAILED_TEXT, b.exactCenterX(), groupTop + size, textPaint);
    }

    /**
     * 量出"画在图片之上、贴住图片底边"的兄弟视图高度(海报卡底部的渐变信息条)。
     * <p>
     * 判定:同一父容器里、排在图片视图之后(后画=在上层)、可见,且
     * ①底边与图片底边齐平、②从图片下半部分才开始(高度不超过卡片一半)、③不是 TextView/ImageView
     * (底部的剧名/备注文字、角标贴图都不是覆盖条)的兄弟,取其中最高的一条。
     * <p>
     * 这样量到的是真实高度(信息条随"集数行"显隐变矮/变高),不会被"图片旁边那列文字"误判;
     * 宿主没有这种覆盖层(详情弹窗海报、没有集数的卡)时返回 0,占位就是纯居中。
     * 新增海报卡布局时按这个形态摆覆盖条即可被自动识别,无需改这里的代码。
     *
     * <p><b>为什么这里要带缓存</b>:本 Drawable 是海报 ImageView 的<b>背景层</b>,加载中的骨架屏扫光
     * ({@code PicassoShimmer},foreground)每帧都会让宿主重画一次 —— 也就是说下面这套
     * {@code indexOfChild} + 遍历兄弟的判定以前是<b>每帧一次</b>。而结论只取决于
     * "宿主几何 + 每个兄弟的身份/可见性/几何",这些量不重新布局就不会变,于是把它们当键缓存:
     * 键没变直接复用上次的高度,键一变(重排、宿主尺寸变化、信息条显隐或变高)立刻按原规则重扫。
     * 判定规则与结果跟每帧重扫完全一致 —— 兄弟的可见性/几何必须一起进键,否则"信息条隐藏后
     * 落回纯居中"(AGENTS §七)这条就会失效。
     */
    private float resolveOverlayReservePx() {
        if (!(getCallback() instanceof View)) {
            overlayReserveValid = false;
            return 0f;
        }
        View self = (View) getCallback();
        ViewParent parent = self.getParent();
        if (!(parent instanceof ViewGroup)) {
            overlayReserveValid = false;
            return 0f;
        }
        ViewGroup group = (ViewGroup) parent;
        // 快速路径:宿主位置/几何与各兄弟都跟上次一样,直接给出上次量到的高度(每帧只做几次整数比较)
        if (overlayReserveFresh(self, group)) return overlayReservePx;
        int index = group.indexOfChild(self);
        if (index < 0) {
            overlayReserveValid = false;
            return 0f;
        }
        int selfBottom = self.getBottom();
        int selfTop = self.getTop();
        int selfHeight = self.getHeight();
        float reserve = 0f;
        if (selfHeight > 0) {
            for (int i = index + 1; i < group.getChildCount(); i++) {
                View sibling = group.getChildAt(i);
                if (sibling == null || sibling.getVisibility() != View.VISIBLE) continue;
                if (sibling instanceof android.widget.TextView || sibling instanceof android.widget.ImageView) continue;
                int h = sibling.getHeight();
                if (h <= 0 || h >= selfHeight) continue;                        // 覆盖条只会占卡片的一部分
                if (Math.abs(sibling.getBottom() - selfBottom) > 1) continue;   // 必须贴着图片底边覆盖
                if (sibling.getTop() < selfTop + selfHeight / 2) continue;      // 且只压住下半部分
                reserve = Math.max(reserve, h);
            }
        }
        rememberOverlayProbe(self, group, index);
        overlayReservePx = reserve;
        overlayReserveValid = true;
        return reserve;
    }

    /**
     * 缓存命中判定(每帧都会走):宿主在父容器里的位置没变、宿主几何没变,
     * 且上次扫过的每个兄弟的"身份 + 可见性 + 高 + 上 + 下"都没变。
     * 任一项不同就返回 false,由 {@link #resolveOverlayReservePx()} 按原规则重扫。
     */
    private boolean overlayReserveFresh(View self, ViewGroup group) {
        if (!overlayReserveValid) return false;
        int index = overlayProbeIndex;
        if (index < 0 || index >= group.getChildCount() || group.getChildAt(index) != self) return false;
        if (self.getWidth() != overlayProbeW || self.getHeight() != overlayProbeH
                || self.getTop() != overlayProbeTop || self.getBottom() != overlayProbeBottom) return false;
        View[] siblings = overlayProbeSiblings;
        int[] states = overlayProbeStates;
        int count = group.getChildCount() - index - 1;
        if (siblings == null || states == null || siblings.length != count) return false;
        for (int i = 0; i < count; i++) {
            View v = group.getChildAt(index + 1 + i);
            int s = i * 4;
            if (v != siblings[i]
                    || v.getVisibility() != states[s]
                    || v.getHeight() != states[s + 1]
                    || v.getTop() != states[s + 2]
                    || v.getBottom() != states[s + 3]) return false;
        }
        return true;
    }

    /** 记下本次扫描的键(宿主几何 + 每个兄弟的身份/可见性/高/上/下),供后续帧快速复用 */
    private void rememberOverlayProbe(View self, ViewGroup group, int index) {
        overlayProbeIndex = index;
        overlayProbeW = self.getWidth();
        overlayProbeH = self.getHeight();
        overlayProbeTop = self.getTop();
        overlayProbeBottom = self.getBottom();
        int count = group.getChildCount() - index - 1;
        if (overlayProbeSiblings == null || overlayProbeSiblings.length != count) {
            overlayProbeSiblings = new View[count];
            overlayProbeStates = new int[count * 4];
        }
        for (int i = 0; i < count; i++) {
            View v = group.getChildAt(index + 1 + i);
            overlayProbeSiblings[i] = v;
            int s = i * 4;
            overlayProbeStates[s] = v.getVisibility();
            overlayProbeStates[s + 1] = v.getHeight();
            overlayProbeStates[s + 2] = v.getTop();
            overlayProbeStates[s + 3] = v.getBottom();
        }
    }

    /**
     * 自身轮廓 = 底色那块圆角矩形:占位放背景层时,ImageView 的 {@code clipToOutline}
     * 会按这个轮廓裁实图(与 XML 占位 layer-list 的轮廓行为一致),圆角卡片里的海报不会露出直角。
     */
    @Override
    public void getOutline(@NonNull Outline outline) {
        Rect b = getBounds();
        if (b.isEmpty()) {
            outline.setEmpty();
            return;
        }
        outline.setRoundRect(b, Math.min(cornerRadiusPx, Math.min(b.width(), b.height()) * 0.5f));
    }

    @Override
    public void setAlpha(int alpha) {
        bgPaint.setAlpha(alpha);
        textPaint.setAlpha(alpha);
        if (icon != null) icon.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        bgPaint.setColorFilter(colorFilter);
        textPaint.setColorFilter(colorFilter);
        if (icon != null) icon.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
