package com.whataicando.touch.win32

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.LRESULT
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.POINT
import com.sun.jna.platform.win32.WinUser.WindowProc
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.awt.Rectangle
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

enum class TouchPhase {
    DOWN, UPDATE, UP, CANCEL
}

data class TouchEvent(
    val id: Int,
    val phase: TouchPhase,
    val xPx: Float,
    val yPx: Float,
    val timeMs: Long
)

// Win32 Constants
const val WM_POINTERUPDATE = 0x0245
const val WM_POINTERDOWN = 0x0246
const val WM_POINTERUP = 0x0247

const val PT_TOUCH = 2

const val POINTER_FLAG_DOWN = 0x00010000
const val POINTER_FLAG_UPDATE = 0x00020000
const val POINTER_FLAG_UP = 0x00040000
const val POINTER_FLAG_CANCELED = 0x00008000

@Structure.FieldOrder(
    "pointerType",
    "pointerId",
    "frameId",
    "pointerFlags",
    "sourceDevice",
    "hwndTarget",
    "ptPixelLocation",
    "ptPixelLocationRaw",
    "ptHimetricLocation",
    "ptHimetricLocationRaw",
    "dwTime",
    "historyCount",
    "InputData",
    "dwKeyStates",
    "PerformanceCount",
    "ButtonChangeType"
)
open class POINTER_INFO : Structure {
    @JvmField var pointerType: Int = 0
    @JvmField var pointerId: Int = 0
    @JvmField var frameId: Int = 0
    @JvmField var pointerFlags: Int = 0
    @JvmField var sourceDevice: Pointer? = null
    @JvmField var hwndTarget: HWND? = null
    @JvmField var ptPixelLocation: POINT = POINT()
    @JvmField var ptPixelLocationRaw: POINT = POINT()
    @JvmField var ptHimetricLocation: POINT = POINT()
    @JvmField var ptHimetricLocationRaw: POINT = POINT()
    @JvmField var dwTime: Int = 0
    @JvmField var historyCount: Int = 0
    @JvmField var InputData: Int = 0
    @JvmField var dwKeyStates: Int = 0
    @JvmField var PerformanceCount: Long = 0
    @JvmField var ButtonChangeType: Int = 0

    constructor() : super()
    constructor(peer: Pointer) : super(peer) {
        read()
    }
}

interface WNDENUMPROC : StdCallLibrary.StdCallCallback {
    fun callback(hWnd: HWND, data: Pointer?): Boolean
}

interface User32Touch : StdCallLibrary {
    fun GetPointerType(pointerId: Int, pointerType: IntByReference): Boolean
    fun GetPointerInfo(pointerId: Int, pointerInfo: POINTER_INFO): Boolean
    fun ScreenToClient(hWnd: HWND, lpPoint: POINT): Boolean
    fun GetAncestor(hWnd: HWND, gaFlags: Int): HWND
    fun EnumChildWindows(hWndParent: HWND, lpEnumFunc: WNDENUMPROC, data: Pointer?): Boolean
    fun GetClassNameW(hWnd: HWND, lpClassName: CharArray, nMaxCount: Int): Int
}

private object Win32Holder {
    val isWindows = System.getProperty("os.name").contains("Windows", ignoreCase = true)
    
    val user32: User32Touch? by lazy {
        if (isWindows) Native.load("user32", User32Touch::class.java) as User32Touch else null
    }
}

object Win32TouchRegistry {
    class HitTestRegion(
        val id: Any,
        var rect: Rectangle,
        var consumeTouch: Boolean = true,
        var depth: Int = 0
    )
    val regions = ConcurrentHashMap<HWND, CopyOnWriteArrayList<HitTestRegion>>()
    private val activePointerIds = ConcurrentHashMap.newKeySet<Int>()

    fun registerRegion(hWnd: HWND, id: Any, rect: Rectangle, consumeTouch: Boolean = true, depth: Int = 0) {
        val list = regions.computeIfAbsent(hWnd) { CopyOnWriteArrayList() }
        val existing = list.firstOrNull { it.id == id }
        if (existing != null) {
            existing.rect = rect
            existing.consumeTouch = consumeTouch
            existing.depth = depth
        } else {
            list.add(HitTestRegion(id, rect, consumeTouch, depth))
        }
    }

    fun unregisterRegion(hWnd: HWND, id: Any) {
        regions[hWnd]?.removeIf { it.id == id }
    }

    fun hitTest(hWnd: HWND, x: Int, y: Int): HitTestRegion? {
        return regions[hWnd]?.filter { it.rect.contains(x, y) }
            ?.maxByOrNull { it.depth }
    }

    private val listeners = ConcurrentHashMap<HWND, CopyOnWriteArrayList<(TouchEvent) -> Unit>>()
    private val prevWndProcs = ConcurrentHashMap<HWND, Pointer>()
    private val subclassCallbacks = ConcurrentHashMap<HWND, WindowProc>()
    private val callbackRetentionSet = ConcurrentHashMap.newKeySet<WindowProc>()
    private const val GWL_WNDPROC = -4

    fun registerListener(hWnd: HWND, listener: (TouchEvent) -> Unit) {
        if (!Win32Holder.isWindows) return
        
        val list = listeners.computeIfAbsent(hWnd) { CopyOnWriteArrayList() }
        list.add(listener)
        
        if (list.size == 1) {
            val callback = object : WindowProc {
                override fun callback(
                    hWnd: HWND,
                    uMsg: Int,
                    wParam: WPARAM,
                    lParam: LPARAM
                ): LRESULT {
                    val user32 = com.sun.jna.platform.win32.User32.INSTANCE
                    val prevProc = prevWndProcs[hWnd]
                    if (prevProc == null) {
                        return user32.DefWindowProc(hWnd, uMsg, wParam, lParam)
                    }
                    
                    when (uMsg) {
                        WM_POINTERDOWN, WM_POINTERUPDATE, WM_POINTERUP -> {
                            val pointerId = (wParam.toLong() and 0xFFFF).toInt()
                            val pointerTypeRef = IntByReference()
                            val customUser32 = Win32Holder.user32 ?: return user32.CallWindowProc(prevProc, hWnd, uMsg, wParam, lParam)
                            
                            val getPointerTypeOk = customUser32.GetPointerType(pointerId, pointerTypeRef)
                            val pointerType = if (getPointerTypeOk) pointerTypeRef.value else -1
                            
                            if (uMsg != WM_POINTERUPDATE) {
                                println("Win32TouchRegistry.callback: HWND = $hWnd, Msg = $uMsg, pointerId = $pointerId, GetPointerTypeOk = $getPointerTypeOk, type = $pointerType")
                            }
                            
                            if (getPointerTypeOk && pointerType == PT_TOUCH) {
                                val pointerInfo = POINTER_INFO()
                                if (customUser32.GetPointerInfo(pointerId, pointerInfo)) {
                                    val ptChild = POINT(pointerInfo.ptPixelLocation.x, pointerInfo.ptPixelLocation.y)
                                    customUser32.ScreenToClient(hWnd, ptChild)

                                    val ptRoot = POINT(pointerInfo.ptPixelLocation.x, pointerInfo.ptPixelLocation.y)
                                    val rootHwnd = customUser32.GetAncestor(hWnd, 2) // GA_ROOT = 2
                                    customUser32.ScreenToClient(rootHwnd, ptRoot)

                                    val isCaptured = activePointerIds.contains(pointerId)

                                    val shouldConsume = if (uMsg == WM_POINTERDOWN) {
                                        val matchedRegion = hitTest(rootHwnd, ptRoot.x, ptRoot.y)
                                        if (matchedRegion != null && matchedRegion.consumeTouch) {
                                            activePointerIds.add(pointerId)
                                            true
                                        } else {
                                            false
                                        }
                                    } else {
                                        isCaptured
                                    }

                                    if (shouldConsume) {
                                        if (uMsg != WM_POINTERUPDATE) {
                                            println("Win32TouchRegistry.callback (consumed): HWND = $hWnd, Root = $rootHwnd, Msg = $uMsg, Screen = (${pointerInfo.ptPixelLocation.x}, ${pointerInfo.ptPixelLocation.y}), ChildClient = (${ptChild.x}, ${ptChild.y})")
                                        }

                                        val flags = pointerInfo.pointerFlags
                                        val phase = when {
                                            (flags and POINTER_FLAG_CANCELED) != 0 -> TouchPhase.CANCEL
                                            uMsg == WM_POINTERDOWN -> TouchPhase.DOWN
                                            uMsg == WM_POINTERUP -> TouchPhase.UP
                                            else -> TouchPhase.UPDATE
                                        }

                                        val event = TouchEvent(
                                            id = pointerId,
                                            phase = phase,
                                            xPx = ptRoot.x.toFloat(),
                                            yPx = ptRoot.y.toFloat(),
                                            timeMs = pointerInfo.dwTime.toLong() and 0xFFFFFFFFL
                                        )

                                         val targets = mutableListOf<(TouchEvent) -> Unit>()
                                         listeners[hWnd]?.let { targets.addAll(it) }
                                         if (rootHwnd != hWnd) {
                                             listeners[rootHwnd]?.let { targets.addAll(it) }
                                         }
                                         if (targets.isNotEmpty()) {
                                             if (javax.swing.SwingUtilities.isEventDispatchThread()) {
                                                 targets.forEach { it(event) }
                                             } else {
                                                 javax.swing.SwingUtilities.invokeLater {
                                                     targets.forEach { it(event) }
                                                 }
                                             }
                                         }

                                        if (uMsg == WM_POINTERUP || phase == TouchPhase.CANCEL) {
                                            activePointerIds.remove(pointerId)
                                        }
                                        return LRESULT(0) // Consume touch event
                                    }
                                }
                            }
                        }
                    }
                    return user32.CallWindowProc(prevProc, hWnd, uMsg, wParam, lParam)
                }
            }
            subclassCallbacks[hWnd] = callback
            val user32 = com.sun.jna.platform.win32.User32.INSTANCE
            val callbackPointer = com.sun.jna.CallbackReference.getFunctionPointer(callback)
            
            // Set new WndProc using SetWindowLongPtr and store the old one
            val oldProc = user32.SetWindowLongPtr(hWnd, GWL_WNDPROC, callbackPointer)
            if (oldProc != null && Pointer.nativeValue(oldProc) != 0L) {
                prevWndProcs[hWnd] = oldProc
                callbackRetentionSet.add(callback) // Keep it alive forever to prevent GC trampoline crashes
            }
            
            val classNameArr = CharArray(256)
            Win32Holder.user32?.GetClassNameW(hWnd, classNameArr, 256)
            val className = String(classNameArr).trimEnd('\u0000')
            println("Win32TouchRegistry: Subclassed HWND $hWnd (class: $className) with SetWindowLongPtr, oldProc: $oldProc")
        }
    }

    fun unregisterListener(hWnd: HWND, listener: (TouchEvent) -> Unit) {
        if (!Win32Holder.isWindows) return
        
        val list = listeners[hWnd] ?: return
        list.remove(listener)
        
        if (list.isEmpty()) {
            listeners.remove(hWnd)
            val callback = subclassCallbacks.remove(hWnd)
            val oldProc = prevWndProcs[hWnd]
            if (callback != null && oldProc != null) {
                val user32 = com.sun.jna.platform.win32.User32.INSTANCE
                user32.SetWindowLongPtr(hWnd, GWL_WNDPROC, oldProc)
                prevWndProcs.remove(hWnd)
                println("Win32TouchRegistry: Unsubclassed HWND $hWnd, restored oldProc: $oldProc")
            }
        }
    }
    
    fun enumChildWindows(hWndParent: HWND): List<HWND> {
        val children = mutableListOf<HWND>()
        val user32 = Win32Holder.user32 ?: return children
        user32.EnumChildWindows(hWndParent, object : WNDENUMPROC {
            override fun callback(hWnd: HWND, data: Pointer?): Boolean {
                children.add(hWnd)
                return true
            }
        }, null)
        return children
    }
}
