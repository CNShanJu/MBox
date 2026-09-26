package com.github.tvbox.osc.ui.kit;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;

import com.github.tvbox.osc.R;

/**
 * 可调尺寸的 compound drawable TextView(项目统一组件)。
 * <p>
 * <b>为什么需要它</b>:布局里 {@code android:drawableRight} 这类 compound drawable 的显示尺寸
 * <b>恒等于素材固有尺寸</b>(vector 的 {@code android:width/height}),TextView 不缩放它,
 * 也没有任何 XML 属性可调 —— 想改大小只能另出一份素材(项目里 {@code ic_sort_18} /
 * {@code ic_change_20} 这类"名字里带尺寸"的由来)。
 * <p>
 * <b>AOSP 依据</b>(android-34 {@code TextView.java})—— 决定图标占位与位置的其实 <b>是 bounds</b>:
 * {@code setCompoundDrawables} 里 {@code left.copyBounds(compoundRect)} 取宽高落到
 * {@code mDrawableSizeRight/HeightRight}(3123-3141 行),{@code onDraw} 按这两个值 translate 后
 * 直接 {@code drawable.draw()}、不再重设 bounds(8946-8963 行);{@code setCompoundDrawablesWithIntrinsicBounds}
 * 只是"先用 intrinsic 把 bounds 设好"再走同一套(3223-3234 行)。
 * 所以"改图标尺寸"必须落到<b>把 drawable 的 bounds(与 intrinsic)一起设成目标尺寸</b>上,
 * 只改其一都会出问题(只改 intrinsic 不生效,只改 bounds 则占位与绘制不一致)。
 * <p>
 * <b>怎么用</b>:把布局里的 {@code <TextView} 换成
 * {@code <com.github.tvbox.osc.ui.kit.SizedIconTextView},再加一个
 * {@code app:drawableIconSize="18dp"};其余写法(android:drawableRight / drawablePadding /
 * ellipsize / 点击监听 / setText)**一律不变**。
 * <p>
 * <b>实现</b>:把 drawable 包一层,构造时同时给出 intrinsic 与 bounds,并把 bounds 转发给被包的 drawable
 * (矢量/位图都会缩放到 bounds)—— 于是占位、位置、绘制三者一起按指定尺寸生效。
 * 不写 {@code app:drawableIconSize}(或写 0dp)时行为与普通 TextView 完全一致。
 * <p>
 * 需要"图标是独立控件"(单独的 tint/动画/显隐)时仍应使用 ImageView + 布局尺寸,那是另一套更直白的做法。
 */
public class SizedIconTextView extends AppCompatTextView {

    /** 图标尺寸(px);0 = 不干预,用素材固有尺寸 */
    private final int iconSizePx;

    public SizedIconTextView(Context context) {
        this(context, null);
    }

    public SizedIconTextView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, android.R.attr.textViewStyle);
    }

    public SizedIconTextView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        int size = 0;
        if (attrs != null) {
            TypedArray ta = context.obtainStyledAttributes(attrs, R.styleable.SizedIconTextView,
                    defStyleAttr, 0);
            size = ta.getDimensionPixelSize(R.styleable.SizedIconTextView_drawableIconSize, 0);
            ta.recycle();
        }
        iconSizePx = Math.max(0, size);
        // XML 里的 drawable 在 super() 里已解析并设过一遍,这里补包一层
        if (iconSizePx > 0) applyIconSize();
    }

    @Override
    public void setCompoundDrawables(@Nullable Drawable left, @Nullable Drawable top,
                                     @Nullable Drawable right, @Nullable Drawable bottom) {
        super.setCompoundDrawables(sized(left), sized(top), sized(right), sized(bottom));
    }

    @Override
    public void setCompoundDrawablesRelative(@Nullable Drawable start, @Nullable Drawable top,
                                             @Nullable Drawable end, @Nullable Drawable bottom) {
        super.setCompoundDrawablesRelative(sized(start), sized(top), sized(end), sized(bottom));
    }

    @Override
    public void setCompoundDrawablesWithIntrinsicBounds(@Nullable Drawable left, @Nullable Drawable top,
                                                       @Nullable Drawable right, @Nullable Drawable bottom) {
        super.setCompoundDrawablesWithIntrinsicBounds(sized(left), sized(top), sized(right), sized(bottom));
    }

    @Override
    public void setCompoundDrawablesRelativeWithIntrinsicBounds(@Nullable Drawable start, @Nullable Drawable top,
                                                               @Nullable Drawable end, @Nullable Drawable bottom) {
        super.setCompoundDrawablesRelativeWithIntrinsicBounds(sized(start), sized(top), sized(end), sized(bottom));
    }

    @Override
    public void setCompoundDrawablesWithIntrinsicBounds(int left, int top, int right, int bottom) {
        super.setCompoundDrawablesWithIntrinsicBounds(left, top, right, bottom);
        // 资源 id 版本要等父类解析完才拿得到 drawable,统一在这里补包
        applyIconSize();
    }

    @Override
    public void setCompoundDrawablesRelativeWithIntrinsicBounds(int start, int top, int end, int bottom) {
        super.setCompoundDrawablesRelativeWithIntrinsicBounds(start, top, end, bottom);
        applyIconSize();
    }

    /** 取当前已设的 drawable、包一层后回设(相对/绝对二选一,见下) */
    private void applyIconSize() {
        if (iconSizePx <= 0) return;
        // 优先相对(start/end):TextView 里设了 drawableStart/End 时 mShowing 也会带上镜像项,
        // 若这时再走 setCompoundDrawables,父类会把 start/end 清掉(AOSP 3060-3067 行)丢 RTL 行为
        Drawable[] rel = getCompoundDrawablesRelative();
        if (hasAny(rel)) {
            super.setCompoundDrawablesRelative(sized(rel[0]), sized(rel[1]), sized(rel[2]), sized(rel[3]));
            return;
        }
        Drawable[] abs = getCompoundDrawables();
        if (hasAny(abs)) {
            super.setCompoundDrawables(sized(abs[0]), sized(abs[1]), sized(abs[2]), sized(abs[3]));
        }
    }

    private static boolean hasAny(Drawable[] ds) {
        for (Drawable d : ds) {
            if (d != null) return true;
        }
        return false;
    }

    private Drawable sized(@Nullable Drawable d) {
        if (d == null || iconSizePx <= 0) return d;
        if (d instanceof FixedIconDrawable && ((FixedIconDrawable) d).sizePx == iconSizePx) return d;
        return new FixedIconDrawable(d, iconSizePx);
    }

    /**
     * 只覆盖"固有尺寸"的包装,并**自带正确 bounds**。
     * <p>
     * 为什么 bounds 必须一起设:TextView 决定图标占位与位置的其实是 bounds,不是 intrinsic ——
     * {@code setCompoundDrawables} 里是 {@code left.copyBounds(compoundRect)} 取宽高
     * (android-34 TextView.java:3123-3141 → {@code mDrawableSizeRight/HeightRight}),
     * {@code onDraw} 再按这两个值 translate 并直接 {@code drawable.draw()}、不再重设 bounds
     * (8946-8963 行);{@code setCompoundDrawablesWithIntrinsicBounds} 只是"先按 intrinsic 设好 bounds"
     * 再走同一套(3223-3234 行)。所以新包的 drawable 若 bounds 为 (0,0,0,0),占位会算成 0、
     * 图标也画不出来(实测症状:图标与文字错位/消失,且改了尺寸不生效)。
     * 这里同时给出 intrinsic 与 bounds,两条路径都成立。
     */
    private static final class FixedIconDrawable extends Drawable {

        private final Drawable src;
        final int sizePx;
        /** 子 drawable 的回调只转一次(TextView 设回调早于首次 onBoundsChange,故放在首次 draw 时转) */
        private boolean callbackForwarded;

        FixedIconDrawable(Drawable src, int sizePx) {
            this.src = src;
            this.sizePx = sizePx;
            src.setBounds(0, 0, sizePx, sizePx);
            setBounds(0, 0, sizePx, sizePx);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            forwardCallbackOnce();
            src.draw(canvas);
        }

        private void forwardCallbackOnce() {
            if (callbackForwarded) return;
            Drawable.Callback cb = getCallback();
            if (cb != null) {
                src.setCallback(cb);
                callbackForwarded = true;
            }
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            src.setBounds(bounds);
        }

        @Override
        public boolean setVisible(boolean visible, boolean restart) {
            boolean changed = super.setVisible(visible, restart);
            src.setVisible(visible, restart);
            return changed;
        }

        @Override
        public void setAlpha(int alpha) {
            src.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            src.setColorFilter(colorFilter);
        }

        @Override
        public void setTintList(@Nullable ColorStateList tint) {
            src.setTintList(tint);
        }

        @Override
        public int getOpacity() {
            return src.getOpacity();
        }

        @Override
        public int getIntrinsicWidth() {
            return sizePx;
        }

        @Override
        public int getIntrinsicHeight() {
            return sizePx;
        }

        @Override
        public boolean isStateful() {
            return src.isStateful();
        }

        @Override
        protected boolean onStateChange(int[] state) {
            return src.setState(state);
        }

        @Override
        protected boolean onLevelChange(int level) {
            return src.setLevel(level);
        }
    }
}
