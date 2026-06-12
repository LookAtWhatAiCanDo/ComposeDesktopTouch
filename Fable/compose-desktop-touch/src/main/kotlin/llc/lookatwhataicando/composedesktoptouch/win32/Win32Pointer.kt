package llc.lookatwhataicando.composedesktoptouch.win32

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.BaseTSD.ULONG_PTR
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.LRESULT
import com.sun.jna.platform.win32.WinDef.POINT
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.platform.win32.WinNT.HANDLE
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

/*
 * JNA mappings for the Win32 pointer-input and window-subclassing APIs that
 * jna-platform does not provide. Nothing in this file (or package) may import
 * Compose types.
 */

/** Window messages and pointer constants (see winuser.h). */
internal object Win32PointerConstants {
    const val WM_TOUCH = 0x0240
    const val WM_GESTURE = 0x0119
    const val WM_POINTERUPDATE = 0x0245
    const val WM_POINTERDOWN = 0x0246
    const val WM_POINTERUP = 0x0247
    const val WM_POINTERCAPTURECHANGED = 0x024C
    const val WM_NCDESTROY = 0x0082
    const val GWLP_WNDPROC = -4

    /**
     * Private message (WM_APP range) posted right after subclass install; the
     * wndproc handles it on the window's owner thread, which is the only
     * thread where UnregisterTouchWindow reliably succeeds.
     */
    const val WM_APP_UNREGISTER_TOUCH = 0x8000 + 0x711

    // POINTER_INPUT_TYPE
    const val PT_POINTER = 1
    const val PT_TOUCH = 2
    const val PT_PEN = 3
    const val PT_MOUSE = 4
    const val PT_TOUCHPAD = 5

    /** GET_POINTERID_WPARAM */
    fun pointerIdFromWParam(wParam: WPARAM): Int = (wParam.toLong() and 0xFFFF).toInt()

    /** GET_X_LPARAM (signed — multi-monitor coordinates can be negative). */
    fun xFromLParam(lParam: LPARAM): Int = (lParam.toLong() and 0xFFFF).toShort().toInt()

    /** GET_Y_LPARAM */
    fun yFromLParam(lParam: LPARAM): Int = ((lParam.toLong() shr 16) and 0xFFFF).toShort().toInt()
}

/**
 * `SUBCLASSPROC` callback (commctrl.h). Invoked by Windows on the thread that
 * owns the window — for AWT windows that is the "AWT-Windows" message-pump
 * thread, so implementations must be fast, non-blocking, and must never throw.
 */
internal interface SUBCLASSPROC : StdCallLibrary.StdCallCallback {
    fun callback(
        hWnd: HWND,
        uMsg: Int,
        wParam: WPARAM,
        lParam: LPARAM,
        uIdSubclass: ULONG_PTR,
        dwRefData: ULONG_PTR,
    ): LRESULT
}

/**
 * `WNDPROC` callback for the raw SetWindowLongPtr subclassing fallback.
 * Same threading rules as [SUBCLASSPROC].
 */
internal interface WNDPROC : StdCallLibrary.StdCallCallback {
    fun callback(hWnd: HWND, uMsg: Int, wParam: WPARAM, lParam: LPARAM): LRESULT
}

/** comctl32.dll subclassing helpers (not mapped by jna-platform). */
internal interface ComCtl32 : StdCallLibrary {
    fun SetWindowSubclass(
        hWnd: HWND,
        pfnSubclass: SUBCLASSPROC,
        uIdSubclass: ULONG_PTR,
        dwRefData: ULONG_PTR,
    ): Boolean

    fun RemoveWindowSubclass(hWnd: HWND, pfnSubclass: SUBCLASSPROC, uIdSubclass: ULONG_PTR): Boolean

    fun DefSubclassProc(hWnd: HWND, uMsg: Int, wParam: WPARAM, lParam: LPARAM): LRESULT

    companion object {
        val INSTANCE: ComCtl32 = Native.load("comctl32", ComCtl32::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }
}

/** user32.dll pointer-input functions missing from jna-platform's User32. */
internal interface User32Pointer : StdCallLibrary {
    /** Pointer type (PT_TOUCH/PT_PEN/...) for a pointer id. Win8+. */
    fun GetPointerType(pointerId: Int, pointerType: IntByReference): Boolean

    /** Full pointer frame info. Win8+. */
    fun GetPointerInfo(pointerId: Int, pointerInfo: POINTER_INFO): Boolean

    fun ScreenToClient(hWnd: HWND, lpPoint: POINT): Boolean

    fun ClientToScreen(hWnd: HWND, lpPoint: POINT): Boolean

    /** True if the window was registered for WM_TOUCH via RegisterTouchWindow. */
    fun IsTouchWindow(hWnd: HWND, pulFlags: IntByReference?): Boolean

    /** Unregisters WM_TOUCH delivery so the window receives WM_POINTER again. */
    fun UnregisterTouchWindow(hWnd: HWND): Boolean

    /**
     * Raw wndproc replacement (GWLP_WNDPROC). Unlike comctl32's
     * SetWindowSubclass, this is legal from any thread of the owning process,
     * which matters because AWT windows are owned by the "AWT-Windows" thread
     * while library code runs on the EDT. Returns the previous wndproc.
     */
    fun SetWindowLongPtrW(hWnd: HWND, nIndex: Int, dwNewLong: WNDPROC): Pointer?

    /** Restore variant of [SetWindowLongPtrW] used on uninstall. */
    fun SetWindowLongPtrW(hWnd: HWND, nIndex: Int, dwNewLong: Pointer): Pointer?

    fun CallWindowProcW(lpPrevWndFunc: Pointer, hWnd: HWND, uMsg: Int, wParam: WPARAM, lParam: LPARAM): LRESULT

    companion object {
        val INSTANCE: User32Pointer = Native.load("user32", User32Pointer::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }
}

/**
 * `POINTER_INFO` (winuser.h). Declared in full for diagnostics/extension;
 * the hot path decodes position from the message's lParam instead.
 */
@Structure.FieldOrder(
    "pointerType", "pointerId", "frameId", "pointerFlags", "sourceDevice", "hwndTarget",
    "ptPixelLocation", "ptHimetricLocation", "ptPixelLocationRaw", "ptHimetricLocationRaw",
    "dwTime", "historyCount", "InputData", "dwKeyStates", "PerformanceCount", "ButtonChangeType",
)
internal class POINTER_INFO : Structure() {
    @JvmField var pointerType: Int = 0
    @JvmField var pointerId: Int = 0
    @JvmField var frameId: Int = 0
    @JvmField var pointerFlags: Int = 0
    @JvmField var sourceDevice: HANDLE? = null
    @JvmField var hwndTarget: HWND? = null
    @JvmField var ptPixelLocation: POINT = POINT()
    @JvmField var ptHimetricLocation: POINT = POINT()
    @JvmField var ptPixelLocationRaw: POINT = POINT()
    @JvmField var ptHimetricLocationRaw: POINT = POINT()
    @JvmField var dwTime: Int = 0
    @JvmField var historyCount: Int = 0
    @JvmField var InputData: Int = 0
    @JvmField var dwKeyStates: Int = 0
    @JvmField var PerformanceCount: Long = 0
    @JvmField var ButtonChangeType: Int = 0
}
