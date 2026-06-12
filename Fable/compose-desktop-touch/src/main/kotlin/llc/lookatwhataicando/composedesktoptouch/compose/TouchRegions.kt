package llc.lookatwhataicando.composedesktoptouch.compose

import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import java.awt.Window
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Job

/**
 * A scrollable container registered for touch hit-testing.
 *
 * [bounds] is updated from `onGloballyPositioned` (in physical px, window
 * coordinates) and read from the native message-pump thread, hence @Volatile.
 * [flingJob] is touched only on the main dispatcher.
 */
internal class TouchRegion(
    val state: ScrollableState,
    val orientation: Orientation,
    val flingDecay: DecayAnimationSpec<Float>,
) {
    @Volatile
    var bounds: Rect = Rect.Zero

    var flingJob: Job? = null
}

/**
 * Global registry of [TouchRegion]s, keyed by their AWT window so that
 * multiple windows with overlapping client coordinates never cross-talk.
 *
 * Thread-safety: written from the main dispatcher (composition effects),
 * read from the native message-pump thread (hit-testing on WM_POINTERDOWN).
 */
internal object TouchRegions {
    private val regionsByWindow = ConcurrentHashMap<Window, CopyOnWriteArrayList<TouchRegion>>()

    /**
     * Regions whose window could not be determined (LocalWindow unavailable —
     * see TouchScrollable). Consulted for every window; only ambiguous when
     * several touch-enabled windows overlap.
     */
    private val windowlessRegions = CopyOnWriteArrayList<TouchRegion>()

    fun register(window: Window?, region: TouchRegion) {
        if (window == null) {
            windowlessRegions.add(region)
        } else {
            regionsByWindow.computeIfAbsent(window) { CopyOnWriteArrayList() }.add(region)
        }
    }

    fun unregister(window: Window?, region: TouchRegion) {
        if (window == null) {
            windowlessRegions.remove(region)
        } else {
            regionsByWindow[window]?.let {
                it.remove(region)
                if (it.isEmpty()) regionsByWindow.remove(window, it)
            }
        }
    }

    /**
     * Region containing [position] (client-area px). When regions nest, the
     * smallest containing region wins, so an inner scrollable beats its parent.
     */
    fun hitTest(window: Window, position: Offset): TouchRegion? {
        val candidates = regionsByWindow[window].orEmpty() + windowlessRegions
        return candidates
            .filter { it.bounds != Rect.Zero && it.bounds.contains(position) }
            .minByOrNull { it.bounds.width * it.bounds.height }
    }
}
