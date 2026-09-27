package com.github.tvbox.osc.ui.kit;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 取色板里的"饱和度 × 明度"方块(HSV 的 S/V 面),配 {@link HueBarView} 用。
 *
 * <p>为什么自己画:取色是"自定义主题"的核心操作,而仓库里没有取色控件、也不值得为此引一个库
 * (AGENTS:依赖能不加就不加)。实现只有三件事:
 * <ul>
 *   <li>底色 = 当前色相的纯色 → 横向叠一层"白→透明"(饱和度);</li>
 *   <li>纵向叠一层"透明→黑"(明度);</li>
 *   <li>触摸点 → (s, v) 并回调,十字准星画在当前位置。</li>
 * </ul>
 *
 * <p>对外只暴露 RGB:{@link #setColor(int)} 会把 rgb 转成 hsv 摆好准星,
 * {@link #getColor()} 给出当前结果(保留 alpha 由调用方另存 —— 色板只管 RGB)。
 */
public class ColorPlateView extends View {

    public interface OnColorChanged {
        /** @param rgb 当前选中的颜色(不含透明度) */
        void onColorChanged(int rgb);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] hsv = new float[]{0f, 1f, 1f};

    private Shader shader;
    private int width;
    private int height;
    private float cursorX = 0f;
    private float cursorY = 0f;
    private OnColorChanged listener;
    private boolean dirty = true;

    public ColorPlateView(Context context) {
        this(context, null);
    }

    public ColorPlateView(Context context, AttributeSet attrs) {
        super(context, attrs);
        cursorPaint.setStyle(Paint.Style.STROKE);
        cursorPaint.setStrokeWidth(dp(2));
        cursorPaint.setColor(Color.WHITE);
        cursorShadow.setStyle(Paint.Style.STROKE);
        cursorShadow.setStrokeWidth(dp(3));
        cursorShadow.setColor(0x80000000);
    }

    public void setOnColorChanged(OnColorChanged l) {
        this.listener = l;
    }

    /** 设置当前颜色(只取 RGB;透明通道由调用方自己维护) */
    public void setColor(int rgb) {
        Color.colorToHSV(rgb | 0xFF000000, hsv);
        dirty = true;
        syncCursor();
        invalidate();
    }

    /** 当前选中的颜色(RGB;alpha 固定为 FF) */
    public int getColor() {
        return Color.HSVToColor(new float[]{hsv[0], hsv[1], hsv[2]});
    }

    /** 色相变了(HueBar 拖动):只换底色,不动 s/v,也不回调(免得上层来回打架) */
    public void setHue(float hue) {
        hsv[0] = hue;
        dirty = true;
        invalidate();
    }

    public float getHue() {
        return hsv[0];
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        width = w;
        height = h;
        dirty = true;
        syncCursor();
    }

    private void syncCursor() {
        cursorX = hsv[1] * width;
        cursorY = (1f - hsv[2]) * height;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (width <= 0 || height <= 0) return;
        if (dirty || shader == null) {
            int pure = Color.HSVToColor(new float[]{hsv[0], 1f, 1f});
            Shader saturation = new LinearGradient(0, 0, width, 0, Color.WHITE, pure, Shader.TileMode.CLAMP);
            Shader value = new LinearGradient(0, 0, 0, height, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP);
            shader = new ComposeShader(saturation, value, PorterDuff.Mode.MULTIPLY);
            dirty = false;
        }
        paint.setShader(shader);
        canvas.drawRect(0, 0, width, height, paint);

        float cx = Math.max(0f, Math.min(width, cursorX));
        float cy = Math.max(0f, Math.min(height, cursorY));
        canvas.drawCircle(cx, cy, dp(7), cursorShadow);
        canvas.drawCircle(cx, cy, dp(7), cursorPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                getParent().requestDisallowInterceptTouchEvent(true);
                updateFromTouch(event.getX(), event.getY());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                updateFromTouch(event.getX(), event.getY());
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void updateFromTouch(float x, float y) {
        float sx = width <= 0 ? 0f : Math.max(0f, Math.min(1f, x / width));
        float vy = height <= 0 ? 0f : Math.max(0f, Math.min(1f, y / height));
        hsv[1] = sx;
        hsv[2] = 1f - vy;
        cursorX = sx * width;
        cursorY = vy * height;
        invalidate();
        if (listener != null) listener.onColorChanged(getColor());
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
