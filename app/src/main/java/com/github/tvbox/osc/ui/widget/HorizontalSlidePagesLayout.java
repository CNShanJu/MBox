package com.github.tvbox.osc.ui.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/**
 * 左右双列"整盒平移"容器(搜索结果页用):来源列表(left,固定 140dp)与结果列表(right)
 * 视为一个整体盒子(逻辑宽 = 结果宽 + 140),滑动时两列作为整体一起水平平移——
 * 列宽/内容永不重排,只是整体位移:
 * - 收起态:来源列收在屏左外(translationX=-140),结果列原样显示(0..结果宽);
 * - 展开态:整盒右移 140,来源列进入左侧,结果列原样右移(适用于屏宽比结果列宽的情形)。
 * 手势:向右横滑展开/向左横滑收起,松手过半自动吸附;遥控端由宿主调用 showLeft()/showRight()。
 */
public class HorizontalSlidePagesLayout extends FrameLayout {

    private View mLeftPage;   // 来源列表(固定 140dp)
    private View mRightPage;  // 结果列表(宽度固定,不参与重排)
    private int mPanelWidth = 0;
    private boolean mShowLeft = false;
    private boolean mDragging = false;
    private float mDownX;
    private float mDownY;
    private final int mTouchSlop;

    public HorizontalSlidePagesLayout(Context context) {
        this(context, null);
    }

    public HorizontalSlidePagesLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    /** 绑定两列:left=来源列表(140dp),right=结果列表(尺寸固定) */
    public void setPages(View leftPage, View rightPage) {
        mLeftPage = leftPage;
        mRightPage = rightPage;
        post(() -> {
            mPanelWidth = mLeftPage.getWidth();
            if (mPanelWidth <= 0) {
                post(() -> setPages(leftPage, rightPage)); // 布局未就绪,重试
                return;
            }
            // 初始收起:来源列屏左外、结果列原位(整盒逻辑宽=结果宽+面板宽)
            mShowLeft = false;
            mLeftPage.setTranslationX(-mPanelWidth);
            mRightPage.setTranslationX(0f);
        });
    }

    /** 是否处于展开(整盒右移、两列并排)状态 */
    public boolean isLeftShown() {
        return mShowLeft;
    }

    /** 展开:整盒右移面板宽,来源列进入左侧 */
    public void showLeft() {
        mShowLeft = true;
        animateBox(mPanelWidth);
    }

    /** 收起:整盒回位,结果列恢复原位 */
    public void showRight() {
        mShowLeft = false;
        animateBox(0f);
    }

    /** offset=0 收起;offset=面板宽 展开。两列同步平移,不改变任何列宽 */
    private void applyOffset(float offset) {
        float o = clamp(offset, 0f, mPanelWidth);
        mLeftPage.setTranslationX(-mPanelWidth + o);
        mRightPage.setTranslationX(o);
    }

    private void animateBox(float offset) {
        if (mLeftPage == null || mRightPage == null || mPanelWidth <= 0) {
            if (mLeftPage != null && mPanelWidth <= 0) {
                post(() -> animateBox(offset));
            }
            return;
        }
        float o = clamp(offset, 0f, mPanelWidth);
        mLeftPage.animate().translationX(-mPanelWidth + o).setDuration(180).start();
        mRightPage.animate().translationX(o).setDuration(180).start();
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = ev.getX();
                mDownY = ev.getY();
                mDragging = false;
                break;
            case MotionEvent.ACTION_MOVE: {
                if (mDragging) return true;
                if (mPanelWidth <= 0) break;
                float dx = ev.getX() - mDownX;
                float dy = ev.getY() - mDownY;
                boolean horizontal = Math.abs(dx) > mTouchSlop && Math.abs(dx) > Math.abs(dy) * 1.2f;
                if (horizontal && ((!mShowLeft && dx > 0) || (mShowLeft && dx < 0))) {
                    mDragging = true;
                    return true;
                }
                break;
            }
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!mDragging || mLeftPage == null || mRightPage == null || mPanelWidth <= 0) {
            return super.onTouchEvent(ev);
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: {
                float base = mShowLeft ? mPanelWidth : 0f;
                float offset = clamp(base + (ev.getX() - mDownX), 0f, mPanelWidth);
                mLeftPage.animate().cancel();
                mRightPage.animate().cancel();
                applyOffset(offset); // 整盒跟手平移
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                mDragging = false;
                float offset = mLeftPage.getTranslationX() + mPanelWidth; // 0=收起 面板宽=展开
                if (offset / mPanelWidth >= 0.5f) showLeft(); else showRight(); // 松手吸附
                break;
            }
        }
        return true;
    }
}
