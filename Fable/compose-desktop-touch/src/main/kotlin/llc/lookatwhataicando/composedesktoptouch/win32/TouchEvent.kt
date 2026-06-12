package llc.lookatwhataicando.composedesktoptouch.win32

/**
 * Lifecycle phase of a single touch contact.
 */
enum class TouchPhase {
    /** Finger made contact with the screen. */
    Down,

    /** Finger moved while in contact. */
    Move,

    /** Finger lifted off the screen. */
    Up,

    /** The contact was lost without a clean lift (e.g. pointer capture changed). */
    Cancel,
}

/**
 * A raw touch contact event decoded from a Win32 `WM_POINTER` message.
 *
 * Coordinates are in physical pixels. [xPx]/[yPx] are relative to the window's
 * client area (which matches Compose's window coordinate space, e.g.
 * `LayoutCoordinates.boundsInWindow`); [xScreenPx]/[yScreenPx] are absolute
 * screen coordinates.
 *
 * @property pointerId Win32 pointer id; stable for the lifetime of one contact.
 * @property phase Lifecycle phase of the contact.
 * @property xPx X position in physical px, relative to the window client area.
 * @property yPx Y position in physical px, relative to the window client area.
 * @property xScreenPx X position in physical px, in screen coordinates.
 * @property yScreenPx Y position in physical px, in screen coordinates.
 * @property timeMs Monotonic timestamp in milliseconds (suitable for velocity tracking).
 */
data class TouchEvent(
    val pointerId: Int,
    val phase: TouchPhase,
    val xPx: Float,
    val yPx: Float,
    val xScreenPx: Float,
    val yScreenPx: Float,
    val timeMs: Long,
)
