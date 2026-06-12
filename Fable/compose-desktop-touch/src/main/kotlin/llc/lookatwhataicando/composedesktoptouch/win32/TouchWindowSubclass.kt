package llc.lookatwhataicando.composedesktoptouch.win32

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.BaseTSD.ULONG_PTR
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.LRESULT
import com.sun.jna.platform.win32.WinDef.POINT
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.ptr.IntByReference

/**
 * Subclasses a window — the top-level frame HWND *and* all of its child
 * HWNDs — and turns its `WM_POINTER` touch messages into [TouchEvent]s.
 *
 * Child windows matter because AWT routes input to the heavyweight component
 * under the contact (for Compose that is the Skiko canvas child window), not
 * to the frame. Event coordinates are always normalized to the frame's client
 * area regardless of which HWND received the message, so they line up with
 * Compose window coordinates.
 *
 * Claiming model: on `WM_POINTERDOWN` the [shouldClaim] predicate is consulted
 * (synchronously, on the message-pump thread) with the contact's client-area
 * position. If it returns false, the message — and every later message for the
 * same pointer id — is passed to the previous window procedure, so Windows
 * mouse-promotes the touch normally (clicks, drags and system gestures outside
 * registered regions keep working with zero interference). If it returns true,
 * the whole contact sequence is consumed (returning 0 suppresses mouse
 * promotion) and surfaced through [onEvent].
 *
 * Mouse and pen pointers are never touched; they always pass through.
 *
 * All callbacks run on the window's owning thread ("AWT-Windows"); they must
 * not block. [onEvent] implementations should hand off to another thread
 * immediately (e.g. `Channel.trySend`).
 */
internal class TouchWindowSubclass(
    private val hwnd: HWND,
    private val shouldClaim: (xClientPx: Float, yClientPx: Float) -> Boolean,
    private val onEvent: (TouchEvent) -> Unit,
    private val onNativeDestroyed: () -> Unit,
) {
    private val comctl = ComCtl32.INSTANCE
    private val user32 = User32Pointer.INSTANCE
    private val subclassId = ULONG_PTR(SUBCLASS_ID)

    /** Pointer ids we claimed at DOWN. Touched only on the pump thread. */
    private val claimedPointers = HashSet<Int>()

    @Volatile
    private var installed = false

    /** HWNDs hooked via comctl32 SetWindowSubclass. */
    private val comctlHooked = java.util.concurrent.CopyOnWriteArrayList<HWND>()

    /** HWND (native value) -> original wndproc, for the SetWindowLongPtr fallback. */
    private val originalProcs = java.util.concurrent.ConcurrentHashMap<Long, Pointer>()

    // Strong references: Windows holds only raw function pointers, so the JVM
    // must be prevented from collecting the callbacks while the subclass lives.
    private val comctlProc = object : SUBCLASSPROC {
        override fun callback(
            hWnd: HWND,
            uMsg: Int,
            wParam: WPARAM,
            lParam: LPARAM,
            uIdSubclass: ULONG_PTR,
            dwRefData: ULONG_PTR,
        ): LRESULT {
            try {
                handled(hWnd, uMsg, wParam, lParam)?.let { return it }
            } catch (t: Throwable) {
                // Never let an exception escape into the native message pump.
                TouchDebugLog.error("wndproc error: $t")
            }
            return comctl.DefSubclassProc(hWnd, uMsg, wParam, lParam)
        }
    }

    private val rawProc = object : WNDPROC {
        override fun callback(hWnd: HWND, uMsg: Int, wParam: WPARAM, lParam: LPARAM): LRESULT {
            val previous = originalProcs[Pointer.nativeValue(hWnd.pointer)]
            try {
                handled(hWnd, uMsg, wParam, lParam)?.let { return it }
            } catch (t: Throwable) {
                TouchDebugLog.error("wndproc error: $t")
            }
            return if (previous != null) {
                user32.CallWindowProcW(previous, hWnd, uMsg, wParam, lParam)
            } else {
                LRESULT(0)
            }
        }
    }

    /** Returns a non-null LRESULT if the message was fully handled. */
    private fun handled(receiver: HWND, uMsg: Int, wParam: WPARAM, lParam: LPARAM): LRESULT? {
        spyOnMessage(receiver, uMsg)
        when (uMsg) {
            Win32PointerConstants.WM_POINTERDOWN,
            Win32PointerConstants.WM_POINTERUPDATE,
            Win32PointerConstants.WM_POINTERUP,
            -> {
                val pointerId = Win32PointerConstants.pointerIdFromWParam(wParam)
                if (!isTouchPointer(pointerId)) return null

                val screenX = Win32PointerConstants.xFromLParam(lParam)
                val screenY = Win32PointerConstants.yFromLParam(lParam)
                // Always relative to the frame's client area (= Compose window
                // coordinates), no matter which child HWND got the message.
                val client = POINT(screenX, screenY)
                user32.ScreenToClient(hwnd, client)

                val phase = when (uMsg) {
                    Win32PointerConstants.WM_POINTERDOWN -> {
                        val claim = shouldClaim(client.x.toFloat(), client.y.toFloat())
                        TouchDebugLog.debug {
                            "WM_POINTERDOWN id=$pointerId client=(${client.x},${client.y}) " +
                                "screen=($screenX,$screenY) claim=$claim"
                        }
                        if (!claim) return null
                        claimedPointers.add(pointerId)
                        TouchPhase.Down
                    }
                    Win32PointerConstants.WM_POINTERUPDATE -> {
                        if (pointerId !in claimedPointers) return null
                        TouchPhase.Move
                    }
                    else -> {
                        if (!claimedPointers.remove(pointerId)) return null
                        TouchPhase.Up
                    }
                }
                onEvent(
                    TouchEvent(
                        pointerId = pointerId,
                        phase = phase,
                        xPx = client.x.toFloat(),
                        yPx = client.y.toFloat(),
                        xScreenPx = screenX.toFloat(),
                        yScreenPx = screenY.toFloat(),
                        timeMs = System.nanoTime() / 1_000_000,
                    ),
                )
                return LRESULT(0)
            }

            Win32PointerConstants.WM_POINTERCAPTURECHANGED -> {
                val pointerId = Win32PointerConstants.pointerIdFromWParam(wParam)
                if (claimedPointers.remove(pointerId)) {
                    onEvent(
                        TouchEvent(
                            pointerId, TouchPhase.Cancel,
                            0f, 0f, 0f, 0f,
                            System.nanoTime() / 1_000_000,
                        ),
                    )
                }
                return null // let the previous proc see it too
            }

            Win32PointerConstants.WM_APP_UNREGISTER_TOUCH -> {
                // Runs on the owner thread (see constant docs). If the JRE
                // registered any of our windows for WM_TOUCH, WM_POINTER is
                // suppressed there until we unregister.
                for (h in allHookedHwnds()) {
                    if (user32.IsTouchWindow(h, null)) {
                        val ok = user32.UnregisterTouchWindow(h)
                        TouchDebugLog.debug {
                            "UnregisterTouchWindow($h) on owner thread -> $ok"
                        }
                    }
                }
                return LRESULT(0)
            }

            Win32PointerConstants.WM_NCDESTROY -> {
                // This HWND is going away; detach from it (we are on the owner
                // thread here, so removal is always legal). When the frame
                // itself dies, the whole installation is finished.
                unhook(receiver)
                if (receiver == hwnd && installed) {
                    installed = false
                    removeNativeHook()
                    onNativeDestroyed()
                }
                return null
            }
        }
        return null
    }

    /** Verbose-only diagnostics: trace input-related messages during bring-up. */
    private fun spyOnMessage(receiver: HWND, uMsg: Int) {
        if (!TouchDebugLog.verbose) return
        val name = when (uMsg) {
            0x0240 -> "WM_TOUCH"
            0x0119 -> "WM_GESTURE"
            0x011A -> "WM_GESTURENOTIFY"
            0x0201 -> "WM_LBUTTONDOWN"
            0x0202 -> "WM_LBUTTONUP"
            0x020A -> "WM_MOUSEWHEEL"
            in 0x0241..0x025F -> "WM_POINTER-range 0x${uMsg.toString(16)}"
            else -> return
        }
        TouchDebugLog.debug { "spy: $name on $receiver" }
    }

    private val pointerTypeRef = IntByReference()

    private fun isTouchPointer(pointerId: Int): Boolean {
        // Pump thread only, so reusing one IntByReference is safe.
        if (!user32.GetPointerType(pointerId, pointerTypeRef)) return false
        return pointerTypeRef.value == Win32PointerConstants.PT_TOUCH
    }

    /**
     * Installs the subclass. Returns false (with a logged GetLastError) if
     * Windows rejected it. Idempotent.
     *
     * Tries comctl32's SetWindowSubclass first; that fails when called from a
     * thread other than the window's owner ("AWT-Windows" owns AWT windows,
     * while we run on the EDT), in which case it falls back to a raw
     * SetWindowLongPtr(GWLP_WNDPROC) chain, which is legal from any thread of
     * the owning process.
     */
    fun install(): Boolean {
        if (installed) return true

        val targets = mutableListOf(hwnd)
        User32.INSTANCE.EnumChildWindows(hwnd, { child, _ ->
            targets.add(child)
            true
        }, Pointer.NULL)
        TouchDebugLog.debug { "hooking ${targets.size} hwnd(s): $targets" }

        var hookedAny = false
        for (target in targets) {
            hookedAny = hook(target) || hookedAny
        }
        installed = hookedAny
        if (installed) {
            // WM_TOUCH unregistration (needed to receive WM_POINTER) must run
            // on the window's owner thread; bounce through our own wndproc.
            User32.INSTANCE.PostMessage(
                hwnd, Win32PointerConstants.WM_APP_UNREGISTER_TOUCH, WPARAM(0), LPARAM(0),
            )
        }
        return installed
    }

    private fun hook(target: HWND): Boolean {
        if (comctl.SetWindowSubclass(target, comctlProc, subclassId, ULONG_PTR(0))) {
            comctlHooked.add(target)
            TouchDebugLog.debug { "hooked $target via SetWindowSubclass" }
            return true
        }
        val previous = user32.SetWindowLongPtrW(target, Win32PointerConstants.GWLP_WNDPROC, rawProc)
        if (previous == null || previous == Pointer.NULL) {
            TouchDebugLog.error(
                "could not hook $target " +
                    "(GetLastError=${Kernel32.INSTANCE.GetLastError()})",
            )
            return false
        }
        originalProcs[Pointer.nativeValue(target.pointer)] = previous
        TouchDebugLog.debug { "hooked $target via SetWindowLongPtr fallback" }
        return true
    }

    private fun unhook(target: HWND) {
        val key = Pointer.nativeValue(target.pointer)
        val previous = originalProcs.remove(key)
        if (previous != null) {
            user32.SetWindowLongPtrW(target, Win32PointerConstants.GWLP_WNDPROC, previous)
        } else if (comctlHooked.remove(target)) {
            comctl.RemoveWindowSubclass(target, comctlProc, subclassId)
        }
    }

    private fun allHookedHwnds(): List<HWND> =
        comctlHooked + originalProcs.keys.map { HWND(Pointer(it)) }

    /** Removes all hooks. Safe to call repeatedly or after window destruction. */
    fun uninstall() {
        installed = false
        removeNativeHook()
    }

    private fun removeNativeHook() {
        for (h in allHookedHwnds()) unhook(h)
    }

    private companion object {
        const val SUBCLASS_ID = 0x7055_0001L // arbitrary tag unique to this library
    }
}

/** Minimal logging: errors always, verbose only with -DcomposeDesktopTouch.debug=true. */
internal object TouchDebugLog {
    val verbose: Boolean = System.getProperty("composeDesktopTouch.debug") == "true"

    fun error(message: String) = System.err.println("[compose-desktop-touch] $message")

    inline fun debug(message: () -> String) {
        if (verbose) println("[compose-desktop-touch] ${message()}")
    }
}
