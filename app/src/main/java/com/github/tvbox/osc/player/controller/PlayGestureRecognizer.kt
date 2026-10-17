package com.github.tvbox.osc.player.controller

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

/**
 * 纯轨迹识别：画圈(中心画圈刷新)，不区分顺逆时针。
 * start 后先观察初始窗口；直线一旦交还普通滑动，本轮不再识别形状。
 */
class PlayGestureRecognizer(density: Float) {
    enum class Gesture { NONE, CIRCLE }

    class Result(val gesture: Gesture, val centerX: Float)

    private enum class Phase { IDLE, UNDECIDED, LINEAR, SHAPE }
    private data class Pt(val x: Float, val y: Float)
    private data class Bend(val span: Double, val deviation: Double)

    private val scale = if (density.isFinite()) density.coerceAtLeast(1f) else 1f
    private val pts = ArrayList<Pt>(MAX_POINTS)
    private var phase = Phase.IDLE
    private var pathLength = 0.0
    private var sampleStep = MIN_SAMPLE_DP * scale.toDouble()
    private var centerX = 0f

    val isActive: Boolean get() = phase == Phase.SHAPE
    val isUndecided: Boolean get() = phase == Phase.UNDECIDED
    val isLinear: Boolean get() = phase == Phase.LINEAR
    internal val pointCount: Int get() = pts.size

    fun start(x: Float, y: Float) {
        reset()
        if (!x.isFinite() || !y.isFinite()) return
        pts.add(Pt(x, y))
        centerX = x
        phase = Phase.UNDECIDED
    }

    fun move(x: Float, y: Float) {
        if (phase != Phase.UNDECIDED && phase != Phase.SHAPE) return
        if (!x.isFinite() || !y.isFinite()) {
            cancel()
            return
        }
        val last = pts.last()
        val distance = hypot(x.toDouble() - last.x, y.toDouble() - last.y)
        // 重复事件/静止抖动不积点；达到上限后稀疏采样，保留起点和最新点。
        if (distance < sampleStep) return
        if (pts.size == MAX_POINTS) compactPoints()
        pathLength += distance
        pts.add(Pt(x, y))
        if (phase == Phase.UNDECIDED) decide()
    }

    private fun decide() {
        // 两点总是共线；低采样率下第一次大位移也不能据此锁死真实圆弧。
        if (pts.size < 3 || pathLength < MIN_PATH_DP * scale) return
        val bend = measureBend()
        val ratio = if (bend.span > 0) bend.deviation / bend.span else 0.0
        val (netSweep, totalSweep) = sweepMetrics()
        val curved = ratio >= BEND_RATIO
        // 明显在画圈：弯曲 且 总转角(折返不抵消)已 >= SHAPE_SWEEP，提前锁定，避免圈前半段被误判成滑动。
        // 用总转角锁 SHAPE 后，recognize 只会更大，不会因折返掉回 NONE。
        if (curved && totalSweep >= SHAPE_SWEEP) {
            phase = Phase.SHAPE
            return
        }
        // 交还普通滑动前必须观察满初始窗口：大半径画圈的起点切向也是"纯轴向"，
        // 24dp 处的局部几何与直线滑动无法区分，提前交还会把画圈误判成滑动且不可逆。
        if (pathLength < INITIAL_WINDOW_DP * scale) return
        // 满窗口后交还：不弯(直线,含穿过质心的斜线与折返) → 走普通滑动；
        // 弯但转向太缓——轨迹隐含半径(路径/净转角)超出屏幕尺度,不可能是本屏画得出来的圆
        // (真实滑动的横向抖动随路径线性增长,画圈的横向累积随路径平方增长,满窗口即可分辨) → 交还；
        // 持续转向的圆弧继续观察,等 SHAPE 锁定,保证各半径画圈不被提前交还。
        val impliedRadius = if (netSweep > 0) pathLength / Math.toRadians(netSweep) else Double.MAX_VALUE
        if (!curved || impliedRadius > MAX_PLAUSIBLE_RADIUS_DP * scale) {
            phase = Phase.LINEAR
            pts.clear()
        }
    }

    /** 相对“起点到最远点”直线的最大偏离量；共线折返的偏离仍然为零。 */
    private fun measureBend(): Bend {
        val start = pts.first()
        val farthest = pts.maxByOrNull {
            val dx = it.x.toDouble() - start.x
            val dy = it.y.toDouble() - start.y
            dx * dx + dy * dy
        } ?: start
        val dx = farthest.x.toDouble() - start.x
        val dy = farthest.y.toDouble() - start.y
        val span = hypot(dx, dy)
        if (span == 0.0) return Bend(0.0, 0.0)
        var deviation = 0.0
        for (p in pts) {
            deviation = max(deviation, abs(dx * (p.y - start.y) - dy * (p.x - start.x)) / span)
        }
        return Bend(span, deviation)
    }

    /**
     * 绕质心的转角，返回 (净转角, 总转角)，单位度。
     * - 净转角 = 相邻角度差(带符号)累加的绝对值：单方向绕圈≈360°，折返会抵消到≈0°。
     * - 总转角 = 相邻角度差绝对值累加：折返不抵消，衡量"总共画了多少弧"。
     * 画圈判定用总转角(>=CIRCLE_SWEEP) + 净转角(>=CIRCLE_SWEEP，排除折返)。
     */
    private fun sweepMetrics(): Pair<Double, Double> {
        if (pts.size < 3) return 0.0 to 0.0
        val cx = pts.sumOf { it.x.toDouble() } / pts.size
        val cy = pts.sumOf { it.y.toDouble() } / pts.size
        var net = 0.0
        var total = 0.0
        var last = atan2(pts[0].y - cy, pts[0].x - cx)
        for (i in 1 until pts.size) {
            val a = atan2(pts[i].y - cy, pts[i].x - cx)
            var d = a - last
            while (d > Math.PI) d -= 2.0 * Math.PI
            while (d < -Math.PI) d += 2.0 * Math.PI
            net += d
            total += abs(d)
            last = a
        }
        return abs(Math.toDegrees(net)) to Math.toDegrees(total)
    }

    /** 结束后回到 IDLE；未 start、cancel 后或重复 finish 均无动作。 */
    fun finish(): Result {
        val gesture = recognize()
        val result = Result(gesture, centerX)
        reset()
        return result
    }

    /** 取消只能丢弃轨迹，不进行识别。 */
    fun cancel() = reset()

    private fun recognize(): Gesture {
        if (phase != Phase.SHAPE || pts.size < 5) {
            return Gesture.NONE
        }
        val cx = pts.sumOf { it.x.toDouble() } / pts.size
        val cy = pts.sumOf { it.y.toDouble() } / pts.size
        centerX = cx.toFloat()

        // 画圈 = 总转角 >= CIRCLE_SWEEP 且 净转角 >= CIRCLE_SWEEP(排除折返) 且 首尾闭合。
        val (netSweep, totalSweep) = sweepMetrics()
        val start = pts.first()
        val end = pts.last()
        val direct = hypot(end.x.toDouble() - start.x, end.y.toDouble() - start.y)
        return if (totalSweep >= CIRCLE_SWEEP && netSweep >= CIRCLE_SWEEP
                && direct <= pathLength * CIRCLE_CLOSE_RATIO) Gesture.CIRCLE else Gesture.NONE
    }

    private fun compactPoints() {
        val last = pts.last()
        var target = 1
        for (i in 2 until pts.lastIndex step 2) pts[target++] = pts[i]
        pts[target++] = last
        while (pts.size > target) pts.removeAt(pts.lastIndex)
        // 跟随实际事件间距扩大采样步长，避免稀疏旧点与密集新点严重失衡。
        sampleStep = max(sampleStep * 2, pathLength / (pts.size - 1))
        pathLength = 0.0
        for (i in 1 until pts.size) {
            pathLength += hypot(pts[i].x.toDouble() - pts[i - 1].x, pts[i].y.toDouble() - pts[i - 1].y)
        }
    }

    private fun reset() {
        pts.clear()
        phase = Phase.IDLE
        pathLength = 0.0
        sampleStep = MIN_SAMPLE_DP * scale.toDouble()
        centerX = 0f
    }

    companion object {
        internal const val MAX_POINTS = 256
        private const val MIN_SAMPLE_DP = 2.0
        private const val MIN_PATH_DP = 24f
        private const val INITIAL_WINDOW_DP = 96f
        /** 弯曲度阈值(dev/span)：低于此视为直线(交还滑动)；调到 0.02 让大半径画圈起笔不被当直线。 */
        private const val BEND_RATIO = 0.02
        private const val CIRCLE_SWEEP = 270.0
        /** 进入形状识别(锁 SHAPE)的最低总转角：画圈前半段即锁定，避免被误判成滑动。 */
        private const val SHAPE_SWEEP = 150.0
        private const val CIRCLE_CLOSE_RATIO = 0.4
        /** 轨迹隐含半径上限(dp)：转向比这更缓的弧不可能是本屏上画得出来的圆，按滑动交还。 */
        private const val MAX_PLAUSIBLE_RADIUS_DP = 1000f
    }
}
