package com.whataicando.touch.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.awt.ComposeWindow
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef.HWND
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.whataicando.touch.win32.TouchEvent
import com.whataicando.touch.win32.TouchPhase
import com.whataicando.touch.win32.Win32TouchRegistry

@Suppress("UNCHECKED_CAST")
private val LocalWindowInstance: androidx.compose.runtime.CompositionLocal<java.awt.Window?>? by lazy {
    try {
        val clazz = Class.forName("androidx.compose.ui.window.LocalAwtWindowKt")
        val method = clazz.getMethod("getLocalAwtWindow")
        method.invoke(null) as androidx.compose.runtime.CompositionLocal<java.awt.Window?>
    } catch (e: Exception) {
        try {
            val clazz = Class.forName("androidx.compose.ui.window.LocalWindowKt")
            val method = clazz.getMethod("getLocalWindow")
            method.invoke(null) as androidx.compose.runtime.CompositionLocal<java.awt.Window?>
        } catch (ex: Exception) {
            println("Win32Touch: Warning - Failed to resolve Compose Window CompositionLocal: ${ex.message}")
            null
        }
    }
}

open class TouchInstallation {
    open fun uninstall() {}
}

private class RealTouchInstallation(val window: ComposeWindow, val hWnd: HWND) : TouchInstallation() {
    private val listener: (TouchEvent) -> Unit = { event ->
        if (javax.swing.SwingUtilities.isEventDispatchThread()) {
            handleNativeTouchEvent(event)
        } else {
            javax.swing.SwingUtilities.invokeLater {
                handleNativeTouchEvent(event)
            }
        }
    }

    private val subclassedHwnds = CopyOnWriteArrayList<HWND>()

    init {
        // Register top level HWND
        subclassedHwnds.add(hWnd)
        Win32TouchRegistry.registerListener(hWnd, listener)
        
        // Find and register all child HWNDs
        val children = Win32TouchRegistry.enumChildWindows(hWnd)
        println("RealTouchInstallation: Found ${children.size} child windows to subclass.")
        for (child in children) {
            subclassedHwnds.add(child)
            Win32TouchRegistry.registerListener(child, listener)
        }
    }

    override fun uninstall() {
        println("RealTouchInstallation: Uninstalling, unsubclassing ${subclassedHwnds.size} windows.")
        for (h in subclassedHwnds) {
            Win32TouchRegistry.unregisterListener(h, listener)
        }
        WindowsTouch.removeInstallation(hWnd)
    }

    private fun handleNativeTouchEvent(event: TouchEvent) {
        val activeContainers = WindowsTouch.containers[hWnd]
        if (activeContainers == null) {
            println("handleNativeTouchEvent: No containers registered for HWND $hWnd")
            return
        }
        val point = Offset(event.xPx, event.yPx)
        
        if (event.phase != TouchPhase.UPDATE) {
            println("handleNativeTouchEvent: event phase = ${event.phase}, id = ${event.id}, pt = $point, containers count = ${activeContainers.size}")
        }

        when (event.phase) {
            TouchPhase.DOWN -> {
                val target = activeContainers.filter { container ->
                    val contains = container.boundsInWindow.contains(point)
                    println("  Hit-test container: bounds = ${container.boundsInWindow}, contains = $contains")
                    contains
                }.maxByOrNull { it.depth }
                if (target != null) {
                    println("  Selected container target: bounds = ${target.boundsInWindow}")
                    target.activePointerId = event.id
                    target.lastXPx = event.xPx
                    target.lastYPx = event.yPx
                    target.lastTimeMs = event.timeMs

                    target.startXPx = event.xPx
                    target.startYPx = event.yPx
                    target.startTimeMs = event.timeMs
                    target.isMaybeTap = true

                    target.velocityTracker.resetTracking()
                    target.velocityTracker.addPosition(event.timeMs, point)

                    target.flingJob?.cancel()
                } else {
                    println("  No container matched the touch-down point $point")
                }
            }
            TouchPhase.UPDATE -> {
                val target = activeContainers.firstOrNull { it.activePointerId == event.id }
                if (target != null) {
                    val dx = event.xPx - target.lastXPx
                    val dy = event.yPx - target.lastYPx

                    target.lastXPx = event.xPx
                    target.lastYPx = event.yPx
                    target.lastTimeMs = event.timeMs

                    target.velocityTracker.addPosition(event.timeMs, point)

                    val distSq = (event.xPx - target.startXPx) * (event.xPx - target.startXPx) +
                                 (event.yPx - target.startYPx) * (event.yPx - target.startYPx)
                    if (distSq > 100f) {
                        target.isMaybeTap = false
                    }

                    val delta = if (target.orientation == Orientation.Vertical) dy else dx
                    target.coroutineScope.launch {
                        target.state.scrollBy(-delta)
                    }
                }
            }
            TouchPhase.UP -> {
                val target = activeContainers.firstOrNull { it.activePointerId == event.id }
                if (target != null) {
                    println("  UP target bounds = ${target.boundsInWindow}")
                    target.activePointerId = null

                    val elapsed = event.timeMs - target.startTimeMs
                    val distSq = (event.xPx - target.startXPx) * (event.xPx - target.startXPx) +
                                 (event.yPx - target.startYPx) * (event.yPx - target.startYPx)

                    if (target.isMaybeTap && elapsed < 300L && distSq <= 100f) {
                        synthesizeClick(event.xPx.toInt(), event.yPx.toInt())
                    } else {
                        val velocityEstimate = target.velocityTracker.calculateVelocity()
                        val velocity = if (target.orientation == Orientation.Vertical) velocityEstimate.y else velocityEstimate.x
                        target.startFling(velocity)
                    }
                }
            }
            TouchPhase.CANCEL -> {
                val target = activeContainers.firstOrNull { it.activePointerId == event.id }
                if (target != null) {
                    target.activePointerId = null
                }
            }
        }
    }

    private fun synthesizeClick(x: Int, y: Int) {
        val time = System.currentTimeMillis()

        // 1. Mouse Pressed
        val pressEvent = MouseEvent(
            window,
            MouseEvent.MOUSE_PRESSED,
            time,
            InputEvent.BUTTON1_DOWN_MASK,
            x,
            y,
            1,
            false,
            MouseEvent.BUTTON1
        )
        window.dispatchEvent(pressEvent)

        // 2. Mouse Released
        val releaseEvent = MouseEvent(
            window,
            MouseEvent.MOUSE_RELEASED,
            time + 10,
            InputEvent.BUTTON1_DOWN_MASK,
            x,
            y,
            1,
            false,
            MouseEvent.BUTTON1
        )
        window.dispatchEvent(releaseEvent)

        // 3. Mouse Clicked
        val clickEvent = MouseEvent(
            window,
            MouseEvent.MOUSE_CLICKED,
            time + 11,
            0,
            x,
            y,
            1,
            false,
            MouseEvent.BUTTON1
        )
        window.dispatchEvent(clickEvent)
    }
}

internal class TouchContainer(
    val state: ScrollableState,
    val orientation: Orientation,
    val flingDecay: DecayAnimationSpec<Float>?,
    val coroutineScope: CoroutineScope,
    val density: Density
) {
    var boundsInWindow: Rect = Rect.Zero
    var depth: Int = 0
    val velocityTracker = VelocityTracker()
    
    var activePointerId: Int? = null
    var lastXPx: Float = 0f
    var lastYPx: Float = 0f
    var lastTimeMs: Long = 0L

    var startXPx: Float = 0f
    var startYPx: Float = 0f
    var startTimeMs: Long = 0L
    var isMaybeTap: Boolean = true

    var flingJob: Job? = null

    fun startFling(initialVelocity: Float) {
        flingJob?.cancel()
        val decay = flingDecay ?: splineBasedDecay(density)

        flingJob = coroutineScope.launch {
            try {
                state.scroll {
                    var lastValue = 0f
                    Animatable(0f).animateDecay(
                        initialVelocity = initialVelocity,
                        animationSpec = decay
                    ) {
                        val delta = value - lastValue
                        lastValue = value
                        scrollBy(-delta)
                    }
                }
            } catch (e: Exception) {
                // Ignore or handle cancellation
            }
        }
    }
}

object WindowsTouch {
    private val installations = ConcurrentHashMap<HWND, RealTouchInstallation>()
    internal val containers = ConcurrentHashMap<HWND, CopyOnWriteArrayList<TouchContainer>>()

    fun install(window: ComposeWindow): TouchInstallation {
        if (!System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
            return TouchInstallation() // No-op installation
        }

        val hwndVal = com.sun.jna.Native.getWindowID(window)
        val hWnd = HWND(Pointer(hwndVal))

        return installations.computeIfAbsent(hWnd) {
            RealTouchInstallation(window, hWnd)
        }
    }

    internal fun removeInstallation(hWnd: HWND) {
        installations.remove(hWnd)
    }
}

/**
 * Registers this container's bounds + state for touch hit-testing.
 */
fun Modifier.touchScrollable(
    state: ScrollableState,
    orientation: Orientation = Orientation.Vertical,
    flingDecay: DecayAnimationSpec<Float>? = null,
    priority: Int = 0
): Modifier = this.composed {
    if (!System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
        return@composed this
    }

    val windowLocal = LocalWindowInstance ?: return@composed this
    val window = windowLocal.current as? ComposeWindow
        ?: return@composed this

    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    val hwndLong = remember(window) { com.sun.jna.Native.getWindowID(window) }
    val hWnd = remember(hwndLong) { HWND(Pointer(hwndLong)) }

    val container = remember(state, orientation, flingDecay, coroutineScope, density) {
        TouchContainer(state, orientation, flingDecay, coroutineScope, density)
    }

    DisposableEffect(hWnd, container) {
        val list = WindowsTouch.containers.computeIfAbsent(hWnd) { CopyOnWriteArrayList() }
        list.add(container)
        onDispose {
            container.flingJob?.cancel()
            list.remove(container)
            Win32TouchRegistry.unregisterRegion(hWnd, container)
            if (list.isEmpty()) {
                WindowsTouch.containers.remove(hWnd)
            }
        }
    }

    this
        .onGloballyPositioned { coordinates ->
            val bounds = coordinates.boundsInWindow()
            container.boundsInWindow = bounds
            
            var depth = priority * 10000
            var curr: androidx.compose.ui.layout.LayoutCoordinates? = coordinates
            while (curr != null) {
                depth++
                curr = curr.parentLayoutCoordinates
            }
            container.depth = depth
            
            val rect = java.awt.Rectangle(
                bounds.left.toInt(),
                bounds.top.toInt(),
                bounds.width.toInt(),
                bounds.height.toInt()
            )
            Win32TouchRegistry.registerRegion(hWnd, container, rect, depth = depth)
        }
}

/**
 * Registers this area as a non-consuming (blocking) touch region.
 * Touch events falling within this region will not drive custom scroll/fling
 * and will instead be delegated back to standard OS mouse-promotion so AWT
 * controls (buttons, text fields, sliders) receive touch clicks and drags normally.
 */
fun Modifier.touchScrim(priority: Int = 0): Modifier = this.composed {
    if (!System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
        return@composed this
    }

    val windowLocal = LocalWindowInstance ?: return@composed this
    val window = windowLocal.current as? ComposeWindow
        ?: return@composed this

    val hwndLong = remember(window) { com.sun.jna.Native.getWindowID(window) }
    val hWnd = remember(hwndLong) { HWND(Pointer(hwndLong)) }

    val regionId = remember { Any() }

    DisposableEffect(hWnd, regionId) {
        onDispose {
            Win32TouchRegistry.unregisterRegion(hWnd, regionId)
        }
    }

    this.onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        
        var depth = priority * 10000
        var curr: androidx.compose.ui.layout.LayoutCoordinates? = coordinates
        while (curr != null) {
            depth++
            curr = curr.parentLayoutCoordinates
        }
        
        val rect = java.awt.Rectangle(
            bounds.left.toInt(),
            bounds.top.toInt(),
            bounds.width.toInt(),
            bounds.height.toInt()
        )
        Win32TouchRegistry.registerRegion(hWnd, regionId, rect, consumeTouch = false, depth = depth)
    }
}
