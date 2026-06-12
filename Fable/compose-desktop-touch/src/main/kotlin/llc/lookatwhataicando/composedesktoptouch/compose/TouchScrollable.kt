package llc.lookatwhataicando.composedesktoptouch.compose

import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import java.awt.Window
import llc.lookatwhataicando.composedesktoptouch.internalIsWindows

/**
 * Registers this container for Windows touchscreen scrolling.
 *
 * Once [WindowsTouch.install] has run for the window, touch contacts that land
 * inside this container's bounds are claimed from the OS (suppressing the
 * legacy wheel-tick mouse promotion) and translated into:
 *  - 1:1 drag scrolling on [state],
 *  - momentum fling on release (a new touch-down cancels an in-flight fling),
 *  - synthesized clicks for taps (within ~10px and 300ms), so clickable
 *    content inside the container keeps working.
 *
 * Apply it alongside the existing scroll modifier, e.g.:
 * ```
 * LazyColumn(
 *     state = listState,
 *     modifier = Modifier.touchScrollable(listState),
 * ) { ... }
 * ```
 *
 * On macOS/Linux this returns the receiver unchanged (no-op), so call sites
 * need no platform branching. Mouse and pen input are never affected.
 *
 * @param state the scroll state to drive ([androidx.compose.foundation.ScrollState]
 *   and lazy list/grid states all implement [ScrollableState]).
 * @param orientation axis to scroll; touch deltas on the other axis are ignored.
 * @param flingDecay decay curve for momentum; defaults to Android-style
 *   [splineBasedDecay] at the window density.
 */
fun Modifier.touchScrollable(
    state: ScrollableState,
    orientation: Orientation = Orientation.Vertical,
    flingDecay: DecayAnimationSpec<Float>? = null,
): Modifier {
    if (!internalIsWindows) return this
    return composed {
        val window = currentAwtWindowOrNull()
        val density = LocalDensity.current
        val decay = flingDecay ?: remember(density) { splineBasedDecay(density) }
        val region = remember(state, orientation, decay) { TouchRegion(state, orientation, decay) }
        DisposableEffect(window, region) {
            TouchRegions.register(window, region)
            onDispose {
                region.flingJob?.cancel()
                TouchRegions.unregister(window, region)
            }
        }
        onGloballyPositioned { region.bounds = it.boundsInWindow() }
    }
}

/*
 * Compose for Desktop has a CompositionLocal carrying the hosting AWT window
 * (androidx.compose.ui.window.LocalWindow), but it is Kotlin-internal as of
 * CMP 1.11. Its backing getter is public JVM bytecode, so it is read via
 * reflection; if a future Compose version removes it, regions fall back to a
 * windowless bucket (see TouchRegions), which only matters for apps showing
 * several touch-enabled windows at once.
 */
@Suppress("UNCHECKED_CAST")
private val reflectedLocalWindow: ProvidableCompositionLocal<Window?>? = runCatching {
    Class.forName("androidx.compose.ui.window.LocalWindowKt")
        .getMethod("getLocalWindow")
        .invoke(null) as ProvidableCompositionLocal<Window?>
}.getOrNull()

@Composable
private fun currentAwtWindowOrNull(): Window? = reflectedLocalWindow?.current
