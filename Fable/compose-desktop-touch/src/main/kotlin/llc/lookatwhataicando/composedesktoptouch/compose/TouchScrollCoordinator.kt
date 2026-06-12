package llc.lookatwhataicando.composedesktoptouch.compose

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.util.VelocityTracker
import java.awt.Toolkit
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import llc.lookatwhataicando.composedesktoptouch.win32.TouchDebugLog
import llc.lookatwhataicando.composedesktoptouch.win32.TouchEvent
import llc.lookatwhataicando.composedesktoptouch.win32.TouchPhase

/**
 * Per-window touch gesture state machine: turns the raw [TouchEvent] stream
 * into scrolls, flings and synthesized clicks on registered [TouchRegion]s.
 *
 * [shouldClaim] is called on the native message-pump thread; everything else
 * runs on the main (Swing) dispatcher via the installation's event loop.
 *
 * Only the first contact is tracked; additional simultaneous fingers are
 * ignored (their messages are claimed-and-dropped by the win32 layer only if
 * they land in a region, otherwise they mouse-promote normally).
 */
internal class TouchScrollCoordinator(
    private val window: ComposeWindow,
    private val scope: CoroutineScope,
) {
    private var activePointerId = NO_POINTER
    private var activeRegion: TouchRegion? = null
    private var downPosition = Offset.Zero
    private var lastPosition = Offset.Zero
    private var downTimeMs = 0L
    private var dragging = false
    private val velocityTracker = VelocityTracker()

    /** Native-thread hit-test: claim the contact only if it lands in a region. */
    fun shouldClaim(xClientPx: Float, yClientPx: Float): Boolean =
        TouchRegions.hitTest(window, Offset(xClientPx, yClientPx)) != null

    fun handleEvent(event: TouchEvent) {
        when (event.phase) {
            TouchPhase.Down -> onDown(event)
            TouchPhase.Move -> onMove(event)
            TouchPhase.Up -> onUp(event)
            TouchPhase.Cancel -> if (event.pointerId == activePointerId) reset()
        }
    }

    private fun onDown(event: TouchEvent) {
        if (activePointerId != NO_POINTER) return // ignore secondary contacts
        val position = Offset(event.xPx, event.yPx)
        val region = TouchRegions.hitTest(window, position) ?: return

        activePointerId = event.pointerId
        activeRegion = region
        downPosition = position
        lastPosition = position
        downTimeMs = event.timeMs
        dragging = false
        velocityTracker.resetTracking()
        velocityTracker.addPosition(event.timeMs, position)

        // A new touch always stops an in-flight fling on the region it hits.
        region.flingJob?.cancel()
        region.flingJob = null
    }

    private fun onMove(event: TouchEvent) {
        if (event.pointerId != activePointerId) return
        val region = activeRegion ?: return
        val position = Offset(event.xPx, event.yPx)
        velocityTracker.addPosition(event.timeMs, position)

        if (!dragging) {
            val travelled = hypot(
                (position.x - downPosition.x).toDouble(),
                (position.y - downPosition.y).toDouble(),
            )
            if (travelled < tapSlopPx()) {
                lastPosition = position
                return
            }
            dragging = true
        }

        val delta = when (region.orientation) {
            Orientation.Vertical -> position.y - lastPosition.y
            Orientation.Horizontal -> position.x - lastPosition.x
        }
        lastPosition = position
        if (delta != 0f) {
            scope.launch { region.state.scrollBy(-delta) }
        }
    }

    private fun onUp(event: TouchEvent) {
        if (event.pointerId != activePointerId) return
        val region = activeRegion ?: return
        val position = Offset(event.xPx, event.yPx)
        velocityTracker.addPosition(event.timeMs, position)

        if (!dragging) {
            if (event.timeMs - downTimeMs < TAP_TIMEOUT_MS) {
                synthesizeClick(position)
            }
            reset()
            return
        }

        val velocity = velocityTracker.calculateVelocity()
        val axisVelocity = when (region.orientation) {
            Orientation.Vertical -> velocity.y
            Orientation.Horizontal -> velocity.x
        }
        reset()

        if (abs(axisVelocity) < MIN_FLING_VELOCITY_PX_PER_S) return
        region.flingJob = scope.launch {
            region.state.scroll(MutatePriority.Default) {
                var consumedSoFar = 0f
                AnimationState(initialValue = 0f, initialVelocity = -axisVelocity)
                    .animateDecay(region.flingDecay) {
                        val delta = value - consumedSoFar
                        val consumed = scrollBy(delta)
                        consumedSoFar += consumed
                        // Hitting the content edge: stop instead of spinning.
                        if (abs(delta - consumed) > 0.5f) cancelAnimation()
                    }
            }
        }
    }

    private fun reset() {
        activePointerId = NO_POINTER
        activeRegion = null
        dragging = false
    }

    private fun windowScale(): Double =
        window.graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0

    private fun tapSlopPx(): Float = (TAP_SLOP_LOGICAL_PX * windowScale()).toFloat()

    /**
     * Posts a synthetic AWT mouse press/release/click at the touch position so
     * taps on claimed regions still activate clickables. AWT coordinates are
     * logical (DPI-scaled), and the window's client-area origin sits at its
     * insets, hence the conversions.
     */
    private fun synthesizeClick(clientPx: Offset) {
        val scale = windowScale()
        val insets = window.insets
        val windowX = (clientPx.x / scale).roundToInt() + insets.left
        val windowY = (clientPx.y / scale).roundToInt() + insets.top

        SwingUtilities.invokeLater {
            val target = SwingUtilities.getDeepestComponentAt(window, windowX, windowY) ?: window
            val point = SwingUtilities.convertPoint(window, windowX, windowY, target)
            val queue = Toolkit.getDefaultToolkit().systemEventQueue
            val time = System.currentTimeMillis()
            TouchDebugLog.debug { "synthesizing click at window=($windowX,$windowY) on ${target.javaClass.simpleName}" }
            queue.postEvent(
                MouseEvent(
                    target, MouseEvent.MOUSE_PRESSED, time, MouseEvent.BUTTON1_DOWN_MASK,
                    point.x, point.y, 1, false, MouseEvent.BUTTON1,
                ),
            )
            queue.postEvent(
                MouseEvent(
                    target, MouseEvent.MOUSE_RELEASED, time, 0,
                    point.x, point.y, 1, false, MouseEvent.BUTTON1,
                ),
            )
            queue.postEvent(
                MouseEvent(
                    target, MouseEvent.MOUSE_CLICKED, time, 0,
                    point.x, point.y, 1, false, MouseEvent.BUTTON1,
                ),
            )
        }
    }

    private companion object {
        const val NO_POINTER = -1
        const val TAP_TIMEOUT_MS = 300L
        const val TAP_SLOP_LOGICAL_PX = 10.0
        const val MIN_FLING_VELOCITY_PX_PER_S = 50f
    }
}
