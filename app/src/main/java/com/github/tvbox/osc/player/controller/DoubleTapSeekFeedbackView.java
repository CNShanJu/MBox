package com.github.tvbox.osc.player.controller;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;

import com.github.tvbox.osc.R;

/** 只绘制双击反馈，不接管触摸；曲边遮罩与命中区使用同一轮廓。 */
public final class DoubleTapSeekFeedbackView extends View {
    private static final long SHOW_MS = 1000L;
    private static final long PULSE_MS = 560L;
    private final Path zonePath = new Path();
    private final RectF zoneOval = new RectF();
    private final Paint shadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pulsePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect labelBounds = new Rect();
    private final Drawable leftIcon;
    private final Drawable rightIcon;
    private int direction;
    private String label;
    private float touchX;
    private float touchY;
    private long shownAt;

    public DoubleTapSeekFeedbackView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        leftIcon = AppCompatResources.getDrawable(context, R.drawable.ic_seek_left);
        rightIcon = AppCompatResources.getDrawable(context, R.drawable.ic_seek_right);
        shadePaint.setColor(ContextCompat.getColor(context, R.color.player_overlay_bg));
        pulsePaint.setColor(Color.BLACK);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        textPaint.setTextSize(14f * getResources().getDisplayMetrics().scaledDensity);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void show(DoubleTapSeekPolicy.Result seek, float x, float y) {
        direction = seek.direction;
        label = seek.moved ? (seek.taps * 10) + "s"
                : (direction < 0 ? "已到开头" : "已到结尾");
        touchX = x;
        touchY = y;
        shownAt = SystemClock.uptimeMillis();
        setVisibility(VISIBLE);
        invalidate();
    }

    void updateTouch(float x, float y) {
        if (getVisibility() != VISIBLE) return;
        touchX = x;
        touchY = y;
        invalidate();
    }

    void hide() {
        setVisibility(GONE);
        direction = 0;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (direction == 0 || getWidth() == 0 || getHeight() == 0) return;
        long age = Math.max(0L, SystemClock.uptimeMillis() - shownAt);
        if (age >= SHOW_MS) return;
        buildZonePath();

        int saved = canvas.save();
        canvas.clipPath(zonePath);
        canvas.drawPath(zonePath, shadePaint);
        drawBackgroundPulse(canvas, age);
        drawLabel(canvas);
        canvas.restoreToCount(saved);
        if (age < PULSE_MS) postInvalidateOnAnimation();
    }

    private void buildZonePath() {
        zonePath.reset();
        float width = getWidth();
        float height = getHeight();
        float radiusX = DoubleTapSeekPolicy.sideRadiusX(width, height);
        float radiusY = DoubleTapSeekPolicy.sideRadiusY(width, height);
        float centerY = height / 2f;
        if (direction < 0) zoneOval.set(-radiusX, centerY - radiusY, radiusX, centerY + radiusY);
        else zoneOval.set(width - radiusX, centerY - radiusY, width + radiusX, centerY + radiusY);
        zonePath.addOval(zoneOval, Path.Direction.CW);
    }

    private void drawBackgroundPulse(Canvas canvas, long age) {
        if (age >= PULSE_MS) return;
        // 连击时重启一层填充扩散，只在曲边背景内生效，不画触点上的独立圆环。
        float progress = age / (float) PULSE_MS;
        float eased = progress * progress * (3f - 2f * progress);
        float radiusX = DoubleTapSeekPolicy.sideRadiusX(getWidth(), getHeight());
        float left = direction < 0 ? 0f : getWidth() - radiusX;
        float right = direction < 0 ? radiusX : getWidth();
        float farthestX = Math.max(Math.abs(touchX - left), Math.abs(touchX - right));
        float farthestY = Math.max(touchY, getHeight() - touchY);
        float startRadius = dp(24f);
        float endRadius = (float) Math.hypot(farthestX, farthestY) + dp(8f);
        pulsePaint.setAlpha(Math.round(40f * (1f - eased)));
        canvas.drawCircle(touchX, touchY,
                startRadius + (endRadius - startRadius) * eased, pulsePaint);
    }

    private void drawLabel(Canvas canvas) {
        float radiusX = DoubleTapSeekPolicy.sideRadiusX(getWidth(), getHeight());
        float centerX = direction < 0 ? radiusX / 2f : getWidth() - radiusX / 2f;
        float centerY = getHeight() / 2f;
        int iconSize = Math.round(dp(12f));
        float gap = dp(2f); // 矢量箭头本身两侧留白，视觉间距约 6dp。
        float rowLeft = centerX - (iconSize + gap + textPaint.measureText(label)) / 2f;
        Drawable icon = direction < 0 ? leftIcon : rightIcon;
        if (icon != null) {
            int x = Math.round(rowLeft);
            int y = Math.round(centerY - iconSize / 2f);
            icon.setBounds(x, y, x + iconSize, y + iconSize);
            icon.draw(canvas);
        }
        textPaint.getTextBounds(label, 0, label.length(), labelBounds);
        float baseline = centerY - (labelBounds.top + labelBounds.bottom) / 2f;
        canvas.drawText(label, rowLeft + iconSize + gap, baseline, textPaint);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
