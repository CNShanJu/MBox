package com.github.tvbox.osc.update;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.util.LOG;

/**
 * 全局更新悬浮圈:任何下载中的时候,无论当前停留在哪个页面,都显示一个圆形进度圈
 * (与 FAB 同观感)。
 * <ul>
 * <li>短按:下载中→暂停,暂停中→继续,失败/完成→打开控制弹窗;</li>
 * <li>长按(约 500ms):任意状态都弹出 {@link UpdateIndicatorDialog} 查看/控制下载;</li>
 * <li>拖拽:移动超过 touchSlop 即取消长按,改为拖动,松手自动吸附到左右边缘,位置跨 Activity 保留。</li>
 * </ul>
 * 附着到当前 Activity 的窗口(decorView 顶部末位),Activity 切换时由 {@code BaseActivity}
 * 的 attach/detach 驱动重新挂载;下载结束/取消即自动移除。
 */
public final class UpdateFloatIndicator implements UpdateManager.Listener {

    private static final String TAG = "UpdateBubble";
    private static volatile UpdateFloatIndicator instance;

    private final Context appContext;
    private View floatView;
    /** 悬浮圈内部自绘视图(创建时直接持有,替代每帧 findView,保证状态刷新稳定) */
    private UpdateBubbleView bubbleView;
    private ViewGroup attachedParent;
    private Activity currentActivity;
    private UpdateIndicatorDialog dialog;

    /** 悬浮圈当前位置(left/top margin,px;初始 null=默认右下角) */
    private Integer posX = null;
    private Integer posY = null;

    // ---- 拖拽状态 ----
    private boolean dragTracking = false;
    private float downX, downY;
    private int dragStartLeft, dragStartTop;
    private final float touchSlop;
    // 长按:DOWN 后延迟触发;一旦移动超过 touchSlop(开始拖动)或抬手即取消
    private final Handler longPressHandler = new Handler(Looper.getMainLooper());
    private final Runnable longPressRunnable = () -> {
        longPressTriggered = true;
        showDialog();
    };
    private boolean longPressTriggered = false;

    private UpdateFloatIndicator(Context context) {
        this.appContext = context.getApplicationContext();
        this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        UpdateManager.get().addListener(this);
    }

    public static synchronized UpdateFloatIndicator get(Context context) {
        if (instance == null) {
            instance = new UpdateFloatIndicator(context);
        }
        return instance;
    }

    /** BaseActivity.onResume 调用:记录当前 Activity 并(如需)挂载悬浮圈 */
    public void attach(Activity activity) {
        if (activity == null)
            return;
        this.currentActivity = activity;
        LOG.i(TAG, "attach " + activity.getClass().getSimpleName()
                + " state=" + UpdateManager.get().getState());
        syncView();
    }

    /** BaseActivity.onPause/onDestroy 调用:当前 Activity 离开前台即卸载悬浮圈(安装/切页时隐藏) */
    public void detach(Activity activity) {
        if (activity != null && activity == currentActivity) {
            LOG.i(TAG, "detach " + activity.getClass().getSimpleName());
            hide();
        }
    }

    /** 当前前台 Activity:优先取 attach 值,兜底用全局 Activity 堆栈顶(避免下载开始时 onResume 未赶上) */
    private Activity resolveActivity(Activity fallback) {
        if (fallback != null && !fallback.isFinishing() && !fallback.isDestroyed())
            return fallback;
        try {
            Activity top = com.github.tvbox.osc.util.AppManager.getInstance().currentActivity();
            if (top != null && !top.isFinishing() && !top.isDestroyed())
                return top;
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ── 状态回调 ──

    @Override
    public void onUpdate(UpdateManager.State state, long downloaded, long total, UpdateInfo info) {
        LOG.i(TAG, "onUpdate state=" + state + " d=" + downloaded + "/" + total
                + " floatView=" + (floatView != null) + " cur="
                + (currentActivity == null ? "null" : currentActivity.getClass().getSimpleName()));
        syncView();
    }

    private void syncView() {
        UpdateManager.State s = UpdateManager.get().getState();
        boolean show = (s == UpdateManager.State.DOWNLOADING
                || s == UpdateManager.State.PAUSED
                || s == UpdateManager.State.COMPLETED
                || s == UpdateManager.State.FAILED);
        Activity a = resolveActivity(currentActivity);
        LOG.i(TAG, "syncView state=" + s + " show=" + show + " act="
                + (a == null ? "null" : a.getClass().getSimpleName())
                + (a == null ? "" : (" fin=" + a.isFinishing() + " des=" + a.isDestroyed()))
                + " attached=" + (floatView != null && floatView.getParent() != null));
        if (!show || a == null || a.isFinishing() || a.isDestroyed()) {
            hide();
            return;
        }
        if (floatView == null) {
            floatView = createFloatView(a);
            // 触摸手势(短按/拖动)由 DragTouchListener 全权处理;OnClickListener 仅兜底
            // 遥控器 OK 键等非触摸点击(触摸路径已消费,不会重复触发)。
            // 不注册 OnLongClickListener:长按计时(约500ms)会在拖动中被中途触发,
            // 造成拖动与弹窗手势互相干扰。
            floatView.setOnClickListener(v -> handleTap());
            floatView.setOnTouchListener(new DragTouchListener());
        }
        if (floatView.getParent() != a.getWindow().getDecorView()) {
            if (floatView.getParent() instanceof ViewGroup) {
                ((ViewGroup) floatView.getParent()).removeView(floatView);
            }
            ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
            int fixed = Math.round(54 * a.getResources().getDisplayMetrics().density);
            // 固定像素尺寸挂载:不能用 WRAP_CONTENT——decor 按"屏宽-边距"给 WRAP 子视图 AT_MOST,
            // 内部 match_parent 的自绘 View 会把可用空间吃满,拖动使边距变小→圆圈随之放大。
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(fixed, fixed, Gravity.NO_GRAVITY);
            placeInitial(lp, a, floatView);
            decor.addView(floatView, lp);
            attachedParent = decor;
            LOG.i(TAG, "bubble mounted to decor(" + a.getClass().getSimpleName() + ")");
        }
        updateBubble();
    }

    /** 构造悬浮气泡:优先 XML inflate;异常或缺少子视图时程序化兜底,并始终持有自绘视图引用 */
    private View createFloatView(Activity a) {
        bubbleView = null;
        View root = null;
        try {
            root = LayoutInflater.from(a).inflate(R.layout.float_update_indicator, null);
        } catch (Throwable t) {
            LOG.e(TAG, "inflate float_update_indicator FAILED, fallback programmatic: " + t);
            LOG.e(TAG, t);
        }
        int size = Math.round(54 * a.getResources().getDisplayMetrics().density);
        UpdateBubbleView b = root == null ? null : root.findViewById(R.id.update_bubble);
        if (b == null) {
            if (!(root instanceof FrameLayout))
                root = new FrameLayout(a);
            FrameLayout fl = (FrameLayout) root;
            fl.setClickable(true);
            fl.setFocusable(true);
            b = new UpdateBubbleView(a);
            b.setId(R.id.update_bubble);
            fl.addView(b, new FrameLayout.LayoutParams(size, size));
            LOG.e(TAG, "update_bubble child missing after inflate, added programmatically");
        }
        bubbleView = b;
        return root;
    }

    /** 首次挂载/无记录位置:默认贴右下(距边 18dp、距底 76dp 避底栏);有记录位置则恢复 */
    private void placeInitial(FrameLayout.LayoutParams lp, Activity a, View view) {
        float density = a.getResources().getDisplayMetrics().density;
        int defEdge = (int) (18 * density);
        int defBottom = (int) (76 * density);
        int viewW = Math.round(54 * density);
        int viewH = Math.round(54 * density);
        if (posX == null || posY == null) {
            lp.leftMargin = defEdge;
            lp.topMargin = defBottom;
        } else {
            lp.leftMargin = Math.max(0, posX);
            lp.topMargin = Math.max(0, posY);
        }
        // NO_GRAVITY 时 left/top 为距左上,right/bottom 为距右下;用距左上即可
        // 默认右下 → left = 屏宽 - edge - viewW,top = 屏高 - bottom - viewH
        if (posX == null || posY == null) {
            android.graphics.Point size = new android.graphics.Point();
            a.getWindowManager().getDefaultDisplay().getSize(size);
            lp.leftMargin = Math.max(0, size.x - defEdge - viewW);
            lp.topMargin = Math.max(0, size.y - defBottom - viewH);
        }
        // 记录相对窗口的实际位置(切 Activity 后窗口尺寸近似,恢复后仍可再拖)
        posX = lp.leftMargin;
        posY = lp.topMargin;
    }

    private void hide() {
        // floatView 对象常驻(listener 在首次创建时已绑定),仅从父容器摘除并暂停动画
        if (floatView != null) {
            LOG.i(TAG, "hide: removing floatView, parent="
                    + (floatView.getParent() == null ? "null" : floatView.getParent().getClass().getSimpleName()));
            UpdateBubbleView b = bubbleView != null ? bubbleView : floatView.findViewById(R.id.update_bubble);
            if (b != null)
                b.pauseAnimations();
            if (floatView.getParent() instanceof ViewGroup) {
                ((ViewGroup) floatView.getParent()).removeView(floatView);
            }
        }
        attachedParent = null;
    }

    /** 用 UpdateBubbleView 映射 UpdateManager 状态与真实进度(进度环/中心图标/动画) */
    private void updateBubble() {
        if (floatView == null) {
            LOG.i(TAG, "updateBubble: floatView null, skipped");
            return;
        }
        UpdateBubbleView b = bubbleView != null ? bubbleView : floatView.findViewById(R.id.update_bubble);
        if (b == null) {
            LOG.i(TAG, "updateBubble: R.id.update_bubble not found in inflated layout!");
            return;
        }
        bubbleView = b;
        UpdateManager m = UpdateManager.get();
        UpdateManager.State s = m.getState();
        long downloaded = m.getDownloaded();
        long total = m.getTotal();
        float progress = total > 0 ? (float) downloaded / (float) total : 0f;
        UpdateBubbleView.BubbleState bs;
        switch (s) {
            case PAUSED:
                bs = UpdateBubbleView.BubbleState.PAUSED;
                break;
            case COMPLETED:
                bs = UpdateBubbleView.BubbleState.COMPLETED;
                break;
            case FAILED:
                bs = UpdateBubbleView.BubbleState.FAILED;
                break;
            case DOWNLOADING:
            default:
                bs = UpdateBubbleView.BubbleState.DOWNLOADING;
                break;
        }
        b.setState(bs, progress);
        LOG.i(TAG, "bubble.setState " + bs + " " + Math.round(progress * 100) + "%");
    }

    /** 短按气泡:下载中→暂停,暂停中→继续;失败/完成/空闲→打开控制弹窗 */
    private void handleTap() {
        UpdateManager.State s = UpdateManager.get().getState();
        if (s == UpdateManager.State.DOWNLOADING) {
            UpdateManager.get().pause();
        } else if (s == UpdateManager.State.PAUSED) {
            UpdateManager.get().resume();
        } else {
            showDialog();
        }
    }

    private void showDialog() {
        if (dialog != null) {
            dialog.dismiss();
        }
        Activity a = currentActivity;
        if (a == null || a.isFinishing() || a.isDestroyed())
            return;
        dialog = new UpdateIndicatorDialog(a);
        dialog.show();
    }

    /**
     * 悬浮圈触摸处理:DOWN 即消费接管整个手势,与 View 自身长按/点击机制隔离。
     * 长按由本类 Handler 延迟调度(标准长按时长),不依赖 View 的 OnLongClickListener——
     * 否则按下约 500ms 后系统长按会触发(哪怕手指已在拖动),弹窗会打断拖动。
     * <ul>
     * <li>按下不动 ≥ 长按时长 → 长按,弹出控制弹窗 {@link #showDialog()};</li>
     * <li>移动超过 touchSlop → 取消长按,改为拖动(只改边距,尺寸固定,松手吸附边缘);</li>
     * <li>未拖动且长按未触发即松手 → 短按 {@link #handleTap()}。</li>
     * </ul>
     */
    private final class DragTouchListener implements View.OnTouchListener {
        @Override
        public boolean onTouch(View v, MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (ev.getPointerCount() > 1)
                        return true; // 多点忽略,防误动
                    dragTracking = false;
                    longPressTriggered = false;
                    downX = ev.getRawX();
                    downY = ev.getRawY();
                    if (v.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
                        dragStartLeft = lp.leftMargin;
                        dragStartTop = lp.topMargin;
                    }
                    // 长按延迟调度:ViewConfiguration 标准长按时长;一旦拖动即取消
                    longPressHandler.removeCallbacks(longPressRunnable);
                    longPressHandler.postDelayed(longPressRunnable,
                            ViewConfiguration.getLongPressTimeout());
                    return true; // 消费 DOWN:长按计时由本 Handler 驱动,避免与系统长按/拖动冲突
                case MotionEvent.ACTION_MOVE: {
                    if (dragTracking) {
                        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
                        ViewGroup parent = (ViewGroup) v.getParent();
                        int maxLeft = parent == null ? 0 : parent.getWidth() - v.getWidth();
                        int maxTop = parent == null ? 0 : parent.getHeight() - v.getHeight();
                        int nx = dragStartLeft + Math.round(ev.getRawX() - downX);
                        int ny = dragStartTop + Math.round(ev.getRawY() - downY);
                        lp.leftMargin = Math.max(0, Math.min(maxLeft, nx));
                        lp.topMargin = Math.max(0, Math.min(maxTop, ny));
                        v.requestLayout();
                        return true;
                    }
                    float dx = ev.getRawX() - downX;
                    float dy = ev.getRawY() - downY;
                    if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) {
                        dragTracking = true;
                        longPressHandler.removeCallbacks(longPressRunnable); // 开始拖动,取消长按
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    longPressHandler.removeCallbacks(longPressRunnable);
                    if (dragTracking) {
                        dragTracking = false;
                        snapToEdge(v);
                    } else if (!longPressTriggered) {
                        handleTap(); // 短按(拖动未开始、长按未触发即松手)
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    longPressHandler.removeCallbacks(longPressRunnable);
                    dragTracking = false; // 系统中断:位置保持,不吸附也不触发点击
                    return true;
                default:
                    return true;
            }
        }

        /** 松手吸附到最近左右边缘,垂直位置保持;记录当前坐标供切页后恢复 */
        private void snapToEdge(View v) {
            if (!(v.getLayoutParams() instanceof FrameLayout.LayoutParams))
                return;
            ViewGroup parent = (ViewGroup) v.getParent();
            if (parent == null)
                return;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
            int edge = Math.round(v.getResources().getDisplayMetrics().density * 8);
            int centerX = lp.leftMargin + v.getWidth() / 2;
            boolean toLeft = centerX < parent.getWidth() / 2;
            lp.leftMargin = toLeft ? edge : Math.max(edge, parent.getWidth() - v.getWidth() - edge);
            v.requestLayout();
            posX = lp.leftMargin;
            posY = lp.topMargin;
        }
    }
}
