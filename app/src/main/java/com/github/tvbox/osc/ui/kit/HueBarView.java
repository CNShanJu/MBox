package com.github.tvbox.osc.ui.kit;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 取色板里的色相条(HSV 的 H),配 {@link ColorPlateView} 用。
 * <p>横向铺满 0~360° 的色相渐变,拖动/点击改色相;圆点指示当前色相。
 */
public class HueBarView extends View {

    public interface OnHueChanged {
        void onHueChanged(float hue);
    }

    private static final int[] HUES = new int[]{
            Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED};

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursor = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private float hue = 0f;
    private OnHueChanged listener;

    public HueBarView(Context context) {
        this(context, null);
    }

    public HueBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        cursor.setStyle(Paint.Style.STROKE);
        cursor.setStrokeWidth(dp(2));
        cursor.setColor(Color.WHITE);
        cursorShadow.setStyle(Paint.Style.STROKE);
        cursorShadow.setStrokeWidth(dp(3));
        cursorShadow.setColor(0x80000000);
    }

    public void setOnHueChanged(OnHueChanged l) {
        this.listener = l;
    }

    public void setHue(float h) {
        this.hue = ((h % 360f) + 360f) % 360f;
        invalidate();
    }

    public float getHue() {
        return hue;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rect.set(0, 0, w, h);
        paint.setShader(new LinearGradient(0, 0, w, 0, HUES, null, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float r = getHeight() / 2f;
        canvas.drawRoundRect(rect, r, r, paint);
        float cx = (hue / 360f) * getWidth();
        cx = Math.max(r, Math.min(getWidth() - r, cx));
        float cy = getHeight() / 2f;
        canvas.drawCircle(cx, cy, r - dp(2), cursorShadow);
        canvas.drawCircle(cx, cy, r - dp(2), cursor);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                getParent().requestDisallowInterceptTouchEvent(true);
                update(event.getX());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                update(event.getX());
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void update(float x) {
        int w = getWidth();
        if (w <= 0) return;
        hue = Math.max(0f, Math.min(359.9f, x / w * 360f));
        invalidate();
        if (listener != null) listener.onHueChanged(hue);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
