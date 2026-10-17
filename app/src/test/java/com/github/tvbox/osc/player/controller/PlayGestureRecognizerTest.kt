package com.github.tvbox.osc.player.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 纯 JVM 轨迹回归；控制器触摸派发/锁定状态仍需人工验证。 */
class PlayGestureRecognizerTest {
    private data class Point(val x: Float, val y: Float)

    @Test
    fun moveAndFinishWithoutStartDoNothing() {
        val recognizer = PlayGestureRecognizer(1f)
        repeat(10) { recognizer.move(it * 20f, 200f) }
        assertIdle(recognizer)
        assertNone(recognizer)
        recognizer.cancel()
        assertNone(recognizer)
    }

    @Test
    fun stationaryAndShortTouchesDoNothing() {
        val recognizer = PlayGestureRecognizer(1f)
        recognizer.start(700f, 200f)
        assertTrue(recognizer.isUndecided)
        repeat(10_000) { recognizer.move(700f, 200f) }
        assertEquals(1, recognizer.pointCount)
        assertNone(recognizer)
        recognizer.start(700f, 200f)
        recognizer.move(707f, 202f)
        recognizer.move(712f, 205f)
        assertTrue(recognizer.isUndecided)
        assertNone(recognizer)
        assertIdle(recognizer)
    }

    @Test
    fun reportedFiveCollinearPointsAreNotAnArc() {
        val recognizer = PlayGestureRecognizer(1f)
        feed(recognizer, listOf(
            Point(700f, 200f), Point(800f, 200f), Point(790f, 200f),
            Point(780f, 200f), Point(770f, 200f)
        ))
        assertTrue(recognizer.isLinear)
        assertNone(recognizer)
    }

    @Test
    fun retracingStraightLinesInAnyOrientationNeverBecomesShape() {
        for (rotation in listOf(0.0, PI / 4, PI / 2, PI * 0.8)) {
            val recognizer = PlayGestureRecognizer(1f)
            val points = (0..160 step 4).toList() + (160 downTo -160 step 4).toList() +
                (-160..160 step 4).toList()
            feed(recognizer, points.map { rotate(it.toDouble(), 0.0, rotation) })
            assertTrue(recognizer.isLinear)
            assertNone(recognizer)
        }
    }

    @Test
    fun shortRepeatedReversalsAlsoLockToLinear() {
        val recognizer = PlayGestureRecognizer(1f)
        recognizer.start(700f, 200f)
        repeat(100) { recognizer.move(if (it % 2 == 0) 720f else 700f, 200f) }
        assertTrue(recognizer.isLinear)
        assertNone(recognizer)
    }

    @Test
    fun linearDecisionCannotBeReclaimedByALaterCircle() {
        val recognizer = PlayGestureRecognizer(1f)
        recognizer.start(0f, 0f)
        for (x in 4..120 step 4) recognizer.move(x.toFloat(), 0f)
        assertTrue(recognizer.isLinear)
        for (point in arc(120.0, 360.0, 360)) {
            recognizer.move(point.x, point.y)
            assertTrue(recognizer.isLinear)
            assertFalse(recognizer.isActive)
            assertFalse(recognizer.isUndecided)
        }
        assertNone(recognizer)
    }

    @Test
    fun circlesWorkInBothDirectionsAtDifferentSizesAndDensities() {
        for (density in listOf(1f, 2f, 3f)) {
            for (radius in listOf(32.0, 80.0, 180.0, 320.0, 480.0)) {
                for (direction in listOf(-1, 1)) {
                    for (samples in listOf(24, 72, 720)) {
                        val recognizer = PlayGestureRecognizer(density)
                        feed(recognizer, arc(radius * density, direction * 360.0, samples))
                        assertTrue("circle r=$radius density=$density dir=$direction samples=$samples", recognizer.isActive)
                        assertEquals(PlayGestureRecognizer.Gesture.CIRCLE, recognizer.finish().gesture)
                    }
                }
            }
        }
    }

    @Test
    fun narrowZigzagsAreRejected() {
        for (rotation in listOf(0.0, PI / 4, PI / 2)) {
            val recognizer = PlayGestureRecognizer(1f)
            val points = (0..80).map { rotate(it * 10.0, if (it % 2 == 0) 0.0 else 4.0, rotation) }
            feed(recognizer, points)
            assertNone(recognizer)
        }
    }

    @Test
    fun thinOutAndBackPolylinesAreNotArcs() {
        for (rotation in listOf(0.0, PI / 4, PI / 2)) {
            val points = (0..60).map { rotate(it * 5.0, it / 10.0, rotation) } +
                (60 downTo 0).map { rotate(it * 5.0, 12.0 - it / 10.0, rotation) }
            val recognizer = PlayGestureRecognizer(1f)
            feed(recognizer, points)
            assertNone(recognizer)
        }
    }

    @Test
    fun cancelDiscardsUndecidedLinearAndShapeGestures() {
        for (points in listOf(
            listOf(Point(1f, 2f)),
            listOf(Point(0f, 0f), Point(60f, 0f), Point(120f, 0f)),
            arc(120.0, 180.0, 120),
            arc(120.0, -360.0, 240)
        )) {
            val recognizer = PlayGestureRecognizer(1f)
            feed(recognizer, points)
            recognizer.cancel()
            for (point in arc(120.0, 360.0, 120)) recognizer.move(point.x, point.y)
            assertIdle(recognizer)
            assertNone(recognizer)
            recognizer.cancel()
            assertNone(recognizer)
        }
    }

    @Test
    fun finishIsOneShotAndNextStartCreatesANewGesture() {
        val recognizer = PlayGestureRecognizer(1f)
        feed(recognizer, arc(120.0, 360.0, 240))
        assertEquals(PlayGestureRecognizer.Gesture.CIRCLE, recognizer.finish().gesture)
        assertIdle(recognizer)
        assertNone(recognizer)
        recognizer.move(700f, 200f)
        assertNone(recognizer)
        feed(recognizer, arc(120.0, -360.0, 120))
        assertEquals(PlayGestureRecognizer.Gesture.CIRCLE, recognizer.finish().gesture)
        assertNone(recognizer)
    }

    @Test
    fun newStartDiscardsAnUnfinishedShape() {
        val recognizer = PlayGestureRecognizer(1f)
        feed(recognizer, arc(120.0, 360.0, 240))
        recognizer.start(100f, 100f)
        assertTrue(recognizer.isUndecided)
        assertEquals(1, recognizer.pointCount)
        assertNone(recognizer)
    }

    @Test
    fun denseCircleRemainsRecognizableWithinPointLimit() {
        for (direction in listOf(-1, 1)) {
            val recognizer = PlayGestureRecognizer(1f)
            feed(recognizer, arc(480.0, direction * 360.0, 40_000))
            val result = recognizer.finish()
            assertEquals(PlayGestureRecognizer.Gesture.CIRCLE, result.gesture)
            assertEquals(700f, result.centerX, 40f)
            assertIdle(recognizer)
        }
    }

    @Test
    fun prolongedMovementDoesNotGrowThePointBuffer() {
        val recognizer = PlayGestureRecognizer(1f)
        feed(recognizer, arc(120.0, 360.0 * 100, 100_000))
        assertTrue(recognizer.isActive)
        recognizer.cancel()
        assertNone(recognizer)
        recognizer.start(0f, 0f)
        repeat(100_000) {
            recognizer.move(it.toFloat(), 0f)
            assertTrue(recognizer.pointCount <= PlayGestureRecognizer.MAX_POINTS)
        }
        assertTrue(recognizer.isLinear)
        assertEquals(0, recognizer.pointCount)
        assertNone(recognizer)
    }

    @Test
    fun invalidCoordinatesCancelRatherThanLeavingAStaleShape() {
        val recognizer = PlayGestureRecognizer(1f)
        recognizer.start(Float.NaN, 0f)
        assertIdle(recognizer)
        feed(recognizer, arc(120.0, 360.0, 240))
        recognizer.move(Float.POSITIVE_INFINITY, 0f)
        assertIdle(recognizer)
        assertNone(recognizer)
    }

    private fun feed(recognizer: PlayGestureRecognizer, points: List<Point>) {
        recognizer.start(points.first().x, points.first().y)
        for (point in points.drop(1)) {
            recognizer.move(point.x, point.y)
            assertTrue(recognizer.pointCount <= PlayGestureRecognizer.MAX_POINTS)
        }
    }

    private fun arc(radius: Double, sweep: Double, samples: Int, rotation: Double = 0.0): List<Point> =
        (0..samples).map {
            val angle = rotation + Math.toRadians(sweep) * it / samples
            Point((700 + radius * cos(angle)).toFloat(), (600 + radius * sin(angle)).toFloat())
        }

    private fun rotate(x: Double, y: Double, angle: Double) =
        Point((700 + x * cos(angle) - y * sin(angle)).toFloat(),
            (600 + x * sin(angle) + y * cos(angle)).toFloat())

    private fun assertNone(recognizer: PlayGestureRecognizer) {
        assertEquals(PlayGestureRecognizer.Gesture.NONE, recognizer.finish().gesture)
    }

    private fun assertIdle(recognizer: PlayGestureRecognizer) {
        assertFalse(recognizer.isActive)
        assertFalse(recognizer.isUndecided)
        assertFalse(recognizer.isLinear)
        assertEquals(0, recognizer.pointCount)
    }
}
