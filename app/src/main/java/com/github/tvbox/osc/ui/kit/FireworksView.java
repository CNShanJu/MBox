package com.github.tvbox.osc.ui.kit;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.AnimationUtils;

import java.util.Random;

/**
 * 一次性烟花层:长按底栏时从按点错峰升起 3 发;启动入场时从底栏 3 或 5 个位置各升起一组。
 * <p>
 * 定位:纯装饰,不参与交互 —— 铺在窗口内容容器({@code android.R.id.content})最上层,不可点击、
 * 不可聚焦,自己不消费触摸(命中返回 false 沿子视图链回落),所以底栏点击/滑动照旧。
 * <p>
 * 长按使用 {@link #celebrate(View, float, float)}(anchor 传被长按的视图,后两个参数是手指按点),
 * 入场使用 {@link #celebrateGroups(View, float[], float)};同一窗口复用同一个实例,
 * 动画放完自己从父容器摘掉,不留常驻视图、不持有 Activity。
 * 系统"动画时长"被关掉时不放(无障碍设置)。
 * <p>
 * 实现要点:物理量一律以 <b>dp</b> 为单位(绘制时 {@code canvas.scale(density)}),换分辨率/换密度
 * 观感一致;帧循环不另起定时器,靠 {@link #postInvalidateOnAnimation()} 跟屏幕刷新率走,视图 detach
 * 即停;粒子从定长池里取(池满覆盖最旧的),升起/炸开期间不新建对象,唯一例外是爆炸闪光那次
 * {@link RadialGradient},每个烟花只建一次。
 */
public class FireworksView extends View {

    private static final String TAG = "mbox_fireworks";

    /** 五组各 3 发可在空中重叠;池满时只覆盖最旧粒子,不覆盖仍在升空的火箭 */
    private static final int POOL_SIZE = 2048;
    private static final int ROCKET_CAPACITY = 48;

    /** 重力(dp/s²):决定升空高度与炸开后下坠的弧度 */
    private static final float GRAVITY = 1700f;
    /** 空气阻力:速度按 e^(-k·t) 衰减,让烟花"炸开后先冲再慢下来"而不是匀减速 */
    private static final float DAMPING = 1.6f;
    /** 升空初速(dp/s):≈ 880²/(2×1700) ≈ 228dp 高,手机上正好在底栏上方空中炸开 */
    private static final float ROCKET_SPEED = 880f;

    /** 烟花色相(每次随机挑几个,整簇近似色、各发略有差别):金/橙/桃红/品红/紫/青/蓝/绿 */
    private static final float[] HUES = {45f, 32f, 340f, 310f, 275f, 190f, 210f, 130f};

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random();
    private final float[] hsv = new float[3];       // HSVToColor 的入参,复用避免热路径分配
    private final Particle[] pool = new Particle[POOL_SIZE];
    private final Rocket[] rockets = new Rocket[ROCKET_CAPACITY];
    private final Flash[] flashes = new Flash[ROCKET_CAPACITY];
    private int poolCursor;

    private final float density;
    private long lastFrameMs;
    private boolean running;

    public FireworksView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        for (int i = 0; i < POOL_SIZE; i++) pool[i] = new Particle();
        for (int i = 0; i < rockets.length; i++) rockets[i] = new Rocket();
        for (int i = 0; i < flashes.length; i++) flashes[i] = new Flash();
        paint.setStrokeCap(Paint.Cap.ROUND);
        // 装饰层:不吃焦点、不进无障碍朗读
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /**
     * 在 anchor 所在窗口的内容层上放一簇烟花,发射点取 anchor 中心(不知道手指在哪时用,如遥控器长按)。
     *
     * @param anchor 作为发射点参照系的视图(整条底栏,或遥控器长按时的那个条目)
     */
    public static void celebrate(View anchor) {
        celebrate(anchor, Float.NaN, Float.NaN);
    }

    /**
     * 在 anchor 所在窗口的内容层上放一簇烟花。
     *
     * @param anchor 作为发射点参照系的视图(整条底栏,或遥控器长按时的那个条目)
     * @param localX 手指按点在 anchor 内的横坐标;NaN=不知道手指在哪,退回 anchor 中心
     * @param localY 手指按点在 anchor 内的纵坐标;NaN 同上
     */
    public static void celebrate(View anchor, float localX, float localY) {
        FireworksView view = overlayFor(anchor);
        if (view == null) return;
        view.burstFrom(anchor, localX, localY);
    }

    /** 三组入场烟花的兼容入口:三个位置各做一次长按式的 3 发动作。 */
    public static boolean celebrateTriple(View navAnchor, float leftX, float middleX,
                                          float rightX, float localY) {
        return celebrateGroups(navAnchor, new float[]{leftX, middleX, rightX}, localY);
    }

    /**
     * 从底栏 3 或 5 个位置各做一次长按式的 3 发动作,总计 9 或 15 发。
     * 第一组立即开始,之后每组比前一组随机晚 100–500ms,允许组间重叠。
     * 坐标均相对 navAnchor;返回 true 表示全部火箭已排入同一动画层。
     */
    public static boolean celebrateGroups(View navAnchor, float[] localXs, float localY) {
        if (navAnchor == null || !navAnchor.isShown()
                || navAnchor.getWindowVisibility() != VISIBLE
                || navAnchor.getWidth() <= 0 || navAnchor.getHeight() <= 0
                || localXs == null || (localXs.length != 3 && localXs.length != 5)
                || Float.isNaN(localY) || Float.isInfinite(localY)) return false;
        for (float localX : localXs) {
            if (Float.isNaN(localX) || Float.isInfinite(localX)) return false;
        }
        FireworksView view = overlayFor(navAnchor);
        return view != null && view.burstGroupsFrom(navAnchor, localXs, localY);
    }

    private static FireworksView overlayFor(View anchor) {
        if (anchor == null) return null;
        // 无障碍/开发者选项里把动画关掉时不放烟花
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ValueAnimator.areAnimatorsEnabled()) return null;
        View root = anchor.getRootView();
        // 已挂到窗口上、且能拿到页面内容容器时才有地方铺:拿不到就不放,宁可没有动画
        if (!anchor.isAttachedToWindow() || !(root instanceof ViewGroup)) return null;
        ViewGroup host = ((ViewGroup) root).findViewById(android.R.id.content);
        if (host == null) return null;
        View found = host.findViewWithTag(TAG);
        FireworksView view = found instanceof FireworksView ? (FireworksView) found : null;
        if (view == null) {
            view = new FireworksView(anchor.getContext());
            view.setTag(TAG);
            host.addView(view, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        return view;
    }

    /** 发射点=手指按点(拿不到手指时取条目中心);本层铺满内容容器,故"容器坐标 = 本层坐标" */
    private void burstFrom(View anchor, float localX, float localY) {
        ViewParent parent = getParent();
        if (!(parent instanceof View)) return;
        View host = (View) parent;
        int[] a = new int[2];
        int[] h = new int[2];
        anchor.getLocationOnScreen(a);
        host.getLocationOnScreen(h);
        float inX = Float.isNaN(localX) ? anchor.getWidth() / 2f : localX;
        float inY = Float.isNaN(localY) ? anchor.getHeight() * 0.3f : localY;
        float cx = (a[0] - h[0] + inX) / density;
        float cy = (a[1] - h[1] + inY) / density;
        launch(cx, cy);
    }

    private boolean burstGroupsFrom(View anchor, float[] localXs, float localY) {
        ViewParent parent = getParent();
        if (!(parent instanceof View)) return false;
        View host = (View) parent;
        int[] a = new int[2];
        int[] h = new int[2];
        anchor.getLocationOnScreen(a);
        host.getLocationOnScreen(h);
        float offsetX = (a[0] - h[0]) / density;
        float cy = (a[1] - h[1] + localY) / density;
        return launchGroups(localXs, offsetX, cy);
    }

    /** 一簇:3 发错峰升空(左右散开、各自一个色调),每发到最高点炸开 */
    private void launch(float cx, float cy) {
        if (!hasFreeRockets(3)) return;
        seedGroup(cx, cy, 0f, false);
        start();
    }

    /** 预留足够槽位后一次性入队,防止重复触发时覆盖仍在空中的火箭。 */
    private boolean launchGroups(float[] localXs, float offsetX, float cy) {
        if (!hasFreeRockets(localXs.length * 3)) return false;
        int[] order = new int[localXs.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        // 每轮独立洗牌,发射点各用一次,不改调用方传入的位置数组。
        for (int i = order.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int swap = order[i];
            order[i] = order[j];
            order[j] = swap;
        }
        float groupDelayMs = 0f;
        for (int i = 0; i < order.length; i++) {
            if (i > 0) groupDelayMs += 100f + random.nextInt(401);
            seedGroup(offsetX + localXs[order[i]] / density, cy, groupDelayMs, true);
        }
        start();
        return true;
    }

    private boolean hasFreeRockets(int count) {
        for (Rocket rocket : rockets) {
            if (!rocket.alive && --count == 0) return true;
        }
        return false;
    }

    private Rocket freeRocket() {
        for (Rocket rocket : rockets) {
            if (!rocket.alive) return rocket;
        }
        throw new IllegalStateException("Firework rocket reservation lost");
    }

    private void seedGroup(float cx, float cy, float groupDelayMs, boolean immediateFirst) {
        int hueStart = random.nextInt(HUES.length);
        for (int i = 0; i < 3; i++) {
            Rocket r = freeRocket();
            r.alive = true;
            r.delay = groupDelayMs + i * 150f
                    + (i == 0 && immediateFirst ? 0 : random.nextInt(40));     // 组内错峰(ms)
            r.x = r.prevX = cx + (i - 1) * (26f + random.nextInt(14));         // 左右拉开一点
            r.y = r.prevY = cy;
            r.vx = (random.nextFloat() - 0.5f) * 40f;                          // 轻微横飘(dp/s)
            r.vy = -(ROCKET_SPEED + random.nextInt(120));
            r.hue = HUES[(hueStart + i * 2) % HUES.length];                     // 同簇近似色
            r.sparkTimer = 0f;
        }
    }

    private void start() {
        if (running) return;
        running = true;
        lastFrameMs = AnimationUtils.currentAnimationTimeMillis();
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now = AnimationUtils.currentAnimationTimeMillis();
        float dtMs = Math.min(48f, Math.max(1f, now - lastFrameMs));  // 单帧步长设上限,防卡顿后跳帧
        lastFrameMs = now;
        boolean active = step(dtMs);

        canvas.save();
        canvas.scale(density, density);   // 之后所有绘制/物理量都以 dp 计
        drawFlashes(canvas);
        drawParticles(canvas);
        drawRockets(canvas);
        canvas.restore();

        if (active) {
            postInvalidateOnAnimation();
        } else {
            running = false;
            clear();
            post(finishRunnable);        // 画完这一帧再摘自己,避免绘制中改父容器
        }
    }

    /** 推进一步物理;返回是否还有活着的火箭/粒子/闪光 */
    private boolean step(float dtMs) {
        float dt = dtMs / 1000f;
        float damp = (float) Math.exp(-DAMPING * dt);
        boolean active = false;

        for (int i = 0; i < rockets.length; i++) {
            Rocket r = rockets[i];
            if (!r.alive) continue;
            active = true;
            if (r.delay > 0f) {          // 还没轮到它升空
                r.delay -= dtMs;
                continue;
            }
            r.prevX = r.x;
            r.prevY = r.y;
            r.vy += GRAVITY * dt;
            r.x += r.vx * dt;
            r.y += r.vy * dt;
            r.sparkTimer -= dtMs;
            if (r.sparkTimer <= 0f) {    // 尾迹火星
                r.sparkTimer = 16f;
                spark(r.x, r.y, r.hue);
            }
            if (r.vy >= 0f) {            // 到最高点:炸开
                r.alive = false;
                explode(r.x, r.y, r.hue);
            }
        }

        for (int i = 0; i < POOL_SIZE; i++) {
            Particle p = pool[i];
            if (!p.alive) continue;
            p.life -= dtMs;
            if (p.life <= 0f) {
                p.alive = false;
                continue;
            }
            active = true;
            p.prevX = p.x;
            p.prevY = p.y;
            p.vy += GRAVITY * dt;
            p.vx *= damp;
            p.vy *= damp;
            p.x += p.vx * dt;
            p.y += p.vy * dt;
        }

        for (int i = 0; i < flashes.length; i++) {
            Flash f = flashes[i];
            if (!f.alive) continue;
            f.life -= dtMs;
            if (f.life <= 0f) {
                f.alive = false;
                f.glow = null;
                continue;
            }
            active = true;
        }
        return active;
    }

    /** 爆炸:一圈粒子(速度按 sqrt 分布,炸成实心球而不是空心环) + 一次爆心闪光 */
    private void explode(float x, float y, float hue) {
        int count = 52 + random.nextInt(12);
        float maxSpeed = 150f + random.nextFloat() * 130f;   // dp/s
        for (int i = 0; i < count; i++) {
            double angle = random.nextDouble() * Math.PI * 2d;
            float speed = maxSpeed * (0.25f + 0.75f * (float) Math.sqrt(random.nextDouble()));
            Particle p = obtain();
            p.x = p.prevX = x;
            p.y = p.prevY = y;
            p.vx = (float) Math.cos(angle) * speed;
            p.vy = (float) Math.sin(angle) * speed;
            p.maxLife = 640f + random.nextFloat() * 460f;
            p.life = p.maxLife;
            p.size = 1.5f + random.nextFloat() * 1.1f;
            p.color = colorOf(hue + jitter(16f), 0.5f + random.nextFloat() * 0.5f);
            p.flicker = random.nextFloat() < 0.35f;          // 一部分粒子闪烁,更像真烟花
        }
        for (int i = 0; i < flashes.length; i++) {
            Flash f = flashes[i];
            if (f.alive) continue;
            f.alive = true;
            f.x = x;
            f.y = y;
            f.radius = 42f + random.nextFloat() * 14f;       // dp
            f.maxLife = 260f;
            f.life = f.maxLife;
            int c = colorOf(hue, 0.45f);
            f.glow = new RadialGradient(x, y, f.radius,
                    new int[]{withAlpha(c, 120), withAlpha(c, 0)},
                    new float[]{0f, 1f}, Shader.TileMode.CLAMP);
            break;
        }
    }

    /** 尾迹火星:慢速下坠的暖色小点 */
    private void spark(float x, float y, float hue) {
        Particle p = obtain();
        p.x = p.prevX = x + jitter(1.2f);
        p.y = p.prevY = y;
        p.vx = jitter(28f);
        p.vy = jitter(28f);
        p.maxLife = 180f + random.nextFloat() * 160f;
        p.life = p.maxLife;
        p.size = 1.4f + random.nextFloat() * 0.8f;
        p.color = colorOf(hue + jitter(14f), 0.55f);
        p.flicker = false;
    }

    /** 从池里取一个粒子:池满就覆盖最旧的 —— 烟花是纯装饰,丢粒子好过掉帧 */
    private Particle obtain() {
        Particle p = pool[poolCursor];
        poolCursor = (poolCursor + 1) % POOL_SIZE;
        p.alive = true;
        return p;
    }

    private void drawFlashes(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < flashes.length; i++) {
            Flash f = flashes[i];
            if (!f.alive || f.glow == null) continue;
            float frac = f.life / f.maxLife;
            paint.setShader(f.glow);
            paint.setAlpha((int) (255f * Math.pow(frac, 1.4d)));
            // 半径由小到大:渐变半径固定,画得越小越"亮心",观感就是爆心在扩散
            canvas.drawCircle(f.x, f.y, f.radius * (0.55f + 0.45f * (1f - frac)), paint);
        }
        paint.setShader(null);
    }

    private void drawParticles(Canvas canvas) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setShader(null);
        for (int i = 0; i < POOL_SIZE; i++) {
            Particle p = pool[i];
            if (!p.alive) continue;
            float frac = p.life / p.maxLife;
            float alpha = (float) Math.pow(frac, 0.6d);
            if (p.flicker) alpha *= 0.55f + 0.45f * (float) Math.sin((p.maxLife - p.life) * 0.045d);
            if (alpha <= 0.02f) continue;
            paint.setColor(p.color);
            paint.setAlpha((int) (alpha * 255f));
            paint.setStrokeWidth(p.size * (0.55f + 0.45f * frac));   // 拖尾越到后面越细
            canvas.drawLine(p.prevX, p.prevY, p.x, p.y, paint);
        }
    }

    private void drawRockets(Canvas canvas) {
        paint.setShader(null);
        paint.setAlpha(255);
        for (int i = 0; i < rockets.length; i++) {
            Rocket r = rockets[i];
            if (!r.alive || r.delay > 0f) continue;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.2f);
            paint.setColor(0xFFFFF3C4);                              // 弹体:暖白
            canvas.drawLine(r.prevX, r.prevY, r.x, r.y, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(r.x, r.y, 1.8f, paint);
        }
    }

    private void clear() {
        for (int i = 0; i < POOL_SIZE; i++) pool[i].alive = false;
        for (int i = 0; i < rockets.length; i++) rockets[i].alive = false;
        for (int i = 0; i < flashes.length; i++) {
            flashes[i].alive = false;
            flashes[i].glow = null;
        }
        poolCursor = 0;
    }

    private final Runnable finishRunnable = new Runnable() {
        @Override
        public void run() {
            if (running) return;   // 期间又放了一簇,继续留着
            ViewParent parent = getParent();
            if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(FireworksView.this);
        }
    };

    @Override
    protected void onDetachedFromWindow() {
        running = false;   // 帧循环靠 invalidate 驱动,detach 后自然不再回调
        clear();
        super.onDetachedFromWindow();
    }

    private float jitter(float range) {
        return (random.nextFloat() - 0.5f) * 2f * range;
    }

    /** 单一色调的醒目颜色(饱和可调,v 固定拉满) */
    private int colorOf(float hue, float saturation) {
        float h = hue % 360f;
        if (h < 0f) h += 360f;
        hsv[0] = h;
        hsv[1] = Math.min(1f, Math.max(0f, saturation));
        hsv[2] = 1f;
        return Color.HSVToColor(hsv);
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static final class Particle {
        float x, y, prevX, prevY, vx, vy, life, maxLife, size;
        int color;
        boolean flicker;
        boolean alive;
    }

    private static final class Rocket {
        float x, y, prevX, prevY, vx, vy, delay, sparkTimer, hue;
        boolean alive;
    }

    private static final class Flash {
        float x, y, radius, life, maxLife;
        Shader glow;
        boolean alive;
    }
}
