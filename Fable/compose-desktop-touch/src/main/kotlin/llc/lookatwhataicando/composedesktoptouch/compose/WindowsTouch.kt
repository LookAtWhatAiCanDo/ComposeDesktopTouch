package llc.lookatwhataicando.composedesktoptouch.compose

import androidx.compose.ui.awt.ComposeWindow
import com.sun.jna.Native
import com.sun.jna.platform.win32.WinDef.HWND
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import llc.lookatwhataicando.composedesktoptouch.internalIsWindows
import llc.lookatwhataicando.composedesktoptouch.win32.TouchEvent
import llc.lookatwhataicando.composedesktoptouch.win32.TouchWindowSubclass

/**
 * Handle for one window's touch support, returned by [WindowsTouch.install].
 */
interface TouchInstallation {
    /**
     * Raw touch events as decoded from `WM_POINTER`, before any gesture
     * interpretation. Only contacts that land on a [touchScrollable] region
     * are claimed and emitted here. Useful for diagnostics/logging.
     */
    val rawEvents: SharedFlow<TouchEvent>

    /**
     * True while native touch handling is live. Always false on non-Windows
     * platforms or when the window subclass could not be installed.
     */
    val isActive: Boolean

    /**
     * Removes the native window subclass and stops event processing.
     * Idempotent; also invoked automatically when the window is disposed.
     */
    fun uninstall()
}

/**
 * Entry point for Windows touchscreen support in Compose for Desktop.
 *
 * Background: AWT has no touch API, so Windows "mouse-promotes" touch pans
 * into discrete synthesized wheel ticks. This library subclasses the native
 * window, handles `WM_POINTER` touch messages itself (which suppresses the
 * promotion), and drives [androidx.compose.foundation.gestures.ScrollableState]
 * with true 1:1 drag, velocity-based fling and tap synthesis.
 */
object WindowsTouch {
    private val installations = ConcurrentHashMap<ComposeWindow, WindowsTouchInstallation>()

    /**
     * Installs touch handling on [window].
     *
     * Call once after the window is showing (e.g. from a `LaunchedEffect`
     * inside the `Window` content). Idempotent — repeated calls for the same
     * window return the same [TouchInstallation]. On macOS/Linux this is a
     * no-op returning an inactive installation, so callers need no platform
     * branching.
     *
     * Scrollable containers opt in via [touchScrollable]; touches outside
     * registered regions keep their default OS behavior.
     */
    fun install(window: ComposeWindow): TouchInstallation {
        if (!internalIsWindows) return NoOpTouchInstallation
        return installations.computeIfAbsent(window) {
            WindowsTouchInstallation(it, onUninstalled = { installations.remove(it) }).apply { start() }
        }
    }
}

private object NoOpTouchInstallation : TouchInstallation {
    override val rawEvents: SharedFlow<TouchEvent> = MutableSharedFlow()
    override val isActive: Boolean = false
    override fun uninstall() = Unit
}

private class WindowsTouchInstallation(
    private val window: ComposeWindow,
    private val onUninstalled: () -> Unit,
) : TouchInstallation {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val events = Channel<TouchEvent>(Channel.UNLIMITED)
    private val mutableRawEvents = MutableSharedFlow<TouchEvent>(extraBufferCapacity = 256)
    private val coordinator = TouchScrollCoordinator(window, scope)
    private var subclass: TouchWindowSubclass? = null

    override val rawEvents: SharedFlow<TouchEvent> = mutableRawEvents.asSharedFlow()

    @Volatile
    override var isActive: Boolean = false
        private set

    private val disposeListener = object : WindowAdapter() {
        override fun windowClosed(e: WindowEvent) = uninstall()
    }

    fun start() {
        check(window.isDisplayable) {
            "WindowsTouch.install must be called after the window is showing"
        }
        val pointer = Native.getComponentPointer(window)
            ?: error("Could not resolve HWND for window")
        val sub = TouchWindowSubclass(
            hwnd = HWND(pointer),
            shouldClaim = coordinator::shouldClaim,
            onEvent = { events.trySend(it) }, // pump thread: hand off, never block
            onNativeDestroyed = { uninstall() },
        )
        isActive = sub.install()
        subclass = sub
        window.addWindowListener(disposeListener)
        scope.launch {
            for (event in events) {
                mutableRawEvents.emit(event)
                coordinator.handleEvent(event)
            }
        }
    }

    override fun uninstall() {
        if (!isActive && subclass == null) return
        isActive = false
        window.removeWindowListener(disposeListener)
        subclass?.uninstall()
        subclass = null
        scope.cancel()
        events.close()
        onUninstalled()
    }
}
