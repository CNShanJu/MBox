package com.github.tvbox.osc.player.controller

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

/**
 * 纯轨迹识别：画圆 / 画弧，不区分顺逆时针。
 * start 后先观察初始窗口；直线一旦交还普通滑动，本轮不再识别形状。
 * 用横向弯曲量而非路径/首尾距离消歧，避免把直线折返当作弧线。
 */
class PlayGestureRecognizer(density: Float) {
    enum class Gesture { NONE, CIRCLE, ARC }

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
        if (bend.deviation >= MIN_BEND_DP * scale && bend.deviation >= bend.span * BEND_RATIO) {
            phase = Phase.SHAPE
        } else if (pathLength >= INITIAL_WINDOW_DP * scale) {
            // 大圆的前 24dp 很像直线：至少观察 96dp；已有轻微弯曲则延长到 160dp。
            // 不能一直等待形状，一旦确认普通滑动，后面的回退/画圈都不能抢回本轮。
            if (bend.deviation <= LINE_NOISE_DP * scale || pathLength >= MAX_WINDOW_DP * scale) {
                phase = Phase.LINEAR
                pts.clear()
            }
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
        if (phase != Phase.SHAPE || pts.size < 5) return Gesture.NONE
        val cx = pts.sumOf { it.x.toDouble() } / pts.size
        val cy = pts.sumOf { it.y.toDouble() } / pts.size
        centerX = cx.toFloat()

        // 协方差的 det / trace² 与旋转无关；排除直线、窄折线、扁平往返轨迹。
        // 仅绕质心 atan2 累加会把穿过质心的共线折返误算成 180°。
        var xx = 0.0
        var yy = 0.0
        var xy = 0.0
        for (p in pts) {
            val dx = p.x - cx
            val dy = p.y - cy
            xx += dx * dx
            yy += dy * dy
            xy += dx * dy
        }
        val trace = xx + yy
        if (trace <= 0.0 || (xx * yy - xy * xy) / (trace * trace) < MIN_AREA_RATIO) {
            return Gesture.NONE
        }

        var total = 0.0
        var lastAngle = atan2(pts[0].y - cy, pts[0].x - cx)
        for (i in 1 until pts.size) {
            val angle = atan2(pts[i].y - cy, pts[i].x - cx)
            var delta = angle - lastAngle
            while (delta > Math.PI) delta -= 2.0 * Math.PI
            while (delta < -Math.PI) delta += 2.0 * Math.PI
            total += delta
            lastAngle = angle
        }
        val swept = abs(Math.toDegrees(total))
        val start = pts.first()
        val end = pts.last()
        val direct = hypot(end.x.toDouble() - start.x, end.y.toDouble() - start.y)
        if (swept >= CIRCLE_SWEEP && direct <= pathLength * CIRCLE_CLOSE_RATIO) return Gesture.CIRCLE
        if (swept >= ARC_SWEEP) return Gesture.ARC
        return Gesture.NONE
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
        private const val MAX_WINDOW_DP = 160f
        private const val LINE_NOISE_DP = 1.5f
        private const val MIN_BEND_DP = 3f
        private const val BEND_RATIO = 0.04
        private const val MIN_AREA_RATIO = 0.015
        private const val CIRCLE_SWEEP = 270.0
        private const val ARC_SWEEP = 90.0
        private const val CIRCLE_CLOSE_RATIO = 0.4
    }
}
