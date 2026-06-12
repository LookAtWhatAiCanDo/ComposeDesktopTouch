# Compose Desktop Touch Support for Windows

This multi-project repository adds native Windows touchscreen and stylus support to
Jetpack/JetBrains Compose for Desktop applications. It contains a decoupled library
module and a sandbox demonstration application.

## Repository Structure

The repository is organized into the following modules:
1. **[:compose-desktop-touch](file:///c:/Dev/GitHub/LookAtWhatAiCanDo/ComposeDesktopTouch/Antigravity/compose-desktop-touch)**:
   A standalone, self-contained library module that implements Win32 WndProc
   subclassing using JNA and provides Compose modifiers for touch interaction.
2. **[:touch-demo](file:///c:/Dev/GitHub/LookAtWhatAiCanDo/ComposeDesktopTouch/Antigravity/touch-demo)**:
   A sandbox application that lets you verify touch behaviors (1:1 dragging, fling
   inertia, click synthesis) and displays real-time `WM_POINTER` events.

---

## Key Features

* **Standalone & Decoupled**: Excludes any internal dependency to other modules,
  allowing modular, decoupled integration.
* **Non-Windows Safe**: Safe to include in multi-platform desktop apps; acts as
  a clean compiled no-op on macOS and Linux.
* **Proper Coordinate Transformation**: Translates screen coordinates using the
  specific drawing canvas HWND (`SunAwtCanvas`) client space, aligning
  perfectly with Compose's `LayoutCoordinates.boundsInWindow()`.
* **Velocity-Based Momentum Fling**: Incorporates Compose's native
  `VelocityTracker` and `splineBasedDecay` animations to deliver an organic,
  high-performance scroll physics experience.
* **Interrupted Fling**: Instantly cancels active fling animations on a new
  touch-down event, mirroring modern mobile OS touch interactions.
* **Tap-to-Click Propagation**: Automatically detects fast taps (<300ms, <10px
  displacement) and dispatches synthesized `MouseEvent`s through the Swing
  dispatcher to trigger buttons and selectable cards normally.
* **Z-Order Dialog Handling**: Bypasses touch consumption on registered dialog
  overlays and scrims (`Modifier.touchScrim`), giving them priority and
  delegating event routing back to native Windows mouse-promotion for standard
  controls.

---

## The Problem

Compose for Desktop (JVM) renders UI inside an AWT heavyweight window. Because
Java's Abstract Window Toolkit (AWT) lacks a touch-specific API, the Windows OS
"mouse-promotes" touchscreen pan gestures into discrete, synthesized
`WM_MOUSEWHEEL` ticks. As of Compose Multiplatform 1.11.0, this results in:
* No contact/finger tracking.
* No touch drag tracking.
* No momentum/fling scrolling.
* Jittery and unresponsive scrolling.

### Legacy AWT & JetBrains Issue History

This limitation stems from two major historical factors:
1. **AWT's Legacy State**: AWT was designed in 1996 (Java 1.0). When Microsoft
   introduced modern multi-touch messaging APIs (`WM_TOUCH` in Windows 7 in
   2009, which was superseded by the `WM_POINTER` API in Windows 8 in 2012),
   AWT's native Windows peers were already in a legacy maintenance-only state.
   Oracle never updated the underlying C/C++ peers of Java's heavyweight frame
   window (`SunAwtFrame`) and canvas (`SunAwtCanvas`) to register for or handle
   pointer input messages. Consequently, standard JVM applications have
   remained blind to Windows touchscreen APIs for over a decade.
2. **Unresolved JetBrains Tracker**: The lack of native touchscreen scrolling in
   Compose for Desktop has been a known and highly requested issue for years.
   It is tracked on GitHub under the primary issue
   [JetBrains/compose-multiplatform#1555](https://github.com/JetBrains/compose-multiplatform/issues/1555)
   (originally opened in early 2022). Due to the complexity of bypassing AWT's
   native window peer handling, no official solution has been shipped to date,
   with issues frequently redirected to YouTrack tickets (such as
   [CMP-1953](https://youtrack.jetbrains.com/issue/CMP-1953) and
   [CMP-1610](https://youtrack.jetbrains.com/issue/CMP-1610)) without
   resolution.

### A Polite Plea to Oracle & JetBrains

If you are an engineer or maintainer working on **OpenJDK (Oracle)** or the
**JetBrains Runtime (JBR)**, we politely appeal to you to fix this limitation
directly at the JDK level. Bypassing this via JNA subclassing is a workaround
that shouldn't have to exist.

#### The Proposed Fix:
To natively resolve this without resorting to runtime WndProc interception:
1. **Handle `WM_POINTER` Messages**: Update AWT's native Windows peer
   implementation (specifically inside `AwtComponent::WindowProc` in
   `awt_Component.cpp`) to capture Win32 `WM_POINTERDOWN`, `WM_POINTERUPDATE`,
   and `WM_POINTERUP` messages.
2. **Propagate Touch Events**: Translate these native pointer inputs into Java
   touch/gesture events (similar to how JavaFX's modern Glass toolkit handles
   them) and dispatch them through AWT's event queue.
3. **Expose a Message-Hooking API**: Alternatively, if translating full
   gestures inside AWT is out of scope, please expose a formal C/Java
   message-hooking API. This would allow third-party library developers to
   cleanly intercept native window messages on child and top-level HWNDs
   without resorting to dangerous, fragile runtime subclassing hacks.

---

## How It Works (Architecture)

To achieve native touch performance without official JDK support, the library
implements a three-tier pointer interception pipeline:

1. **JNA WndProc Subclassing**:
   The library registers a native subclass Window Procedure (`WndProc`) using
   Java Native Access (JNA) on the top-level AWT `SunAwtFrame` window and
   recursively hooks all child drawing canvas (`SunAwtCanvas`) HWNDs. This allows
   us to intercept Win32 pointer messages (`WM_POINTERDOWN`, `WM_POINTERUPDATE`,
   `WM_POINTERUP`) directly.
2. **Reverse Z-Order Hit-Testing**:
   When a touch-down (`WM_POINTERDOWN`) occurs, the library checks the bounds
   of all registered containers in **reverse composition order** (the last
   composed elements / front-most overlays are hit-tested first).
3. **Selective Event Consumption**:
   * **Scrollable Containers (`Modifier.touchScrollable`)**: If the touch-down
     coordinate falls inside a registered scrollable bounds, the `pointerId` is
     captured, and subsequent movement/up events are consumed (`LRESULT(0)`).
     Raw movements are routed directly to the Compose scroll animation pipeline
     to drive 1:1 dragging and velocity-based momentum flinging.
   * **Scrims & Dialogs (`Modifier.touchScrim`)**: If the touch-down coordinate
     falls inside a registered dialog scrim, it matches the scrim region first
     due to reverse order hit-testing. However, because scrims are registered
     as non-consuming, JNA bypasses capture.
   * **AWT Mouse Promotion**: Any touch event that is not consumed (including
     touches on non-consuming dialog scrims, buttons, text fields, or areas
     outside scrollable containers) falls back to default OS behavior.
     Windows automatically mouse-promotes the pointer events into standard AWT
     mouse events (press, drag, release), ensuring controls receive clicks and
     focus normally.

---

## Setup & API Usage

### 1. Installation

Call `WindowsTouch.install` in your application setup once the AWT window
becomes displayable:

```kotlin
import llc.lookatwhataicando.touch.compose.WindowsTouch

Window(
    onCloseRequest = ::exitApplication,
    title = "My App"
) {
    val windowInstance = window
    LaunchedEffect(windowInstance) {
        // Wait until AWT window peer is initialized
        while (!windowInstance.isDisplayable) {
            delay(10)
        }
        WindowsTouch.install(windowInstance)
    }
    
    // UI Code...
}
```

### 2. Scrollable Modifiers

Apply `.touchScrollable` to scrollable containers (e.g., `LazyColumn`, `Column`
with `verticalScroll`) alongside mouse/scrollbar support:

```kotlin
import llc.lookatwhataicando.touch.compose.touchScrollable

val lazyListState = rememberLazyListState()

LazyColumn(
    state = lazyListState,
    modifier = Modifier
        .fillMaxSize()
        .touchScrollable(lazyListState) // Enables smooth 1:1 touch dragging and fling
) {
    items(100) { index ->
        Text("Item #$index")
    }
}
```

### 3. Dialogs, Overlays, and Scrims (`Modifier.touchScrim`)

Apply the `.touchScrim()` modifier to your dialog or overlay's root container
so that standard controls inside the dialog receive mouse-promoted events, and
underlying background lists do not scroll:

```kotlin
import llc.lookatwhataicando.touch.compose.touchScrim

@Composable
fun MyDialog(onClose: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .touchScrim() // Registers a non-consuming block; background lists are ignored
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable { onClose() },
        contentAlignment = Alignment.Center
    ) {
        Card {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Foreground Dialog")
                Button(onClick = { /* ... */ }) {
                    Text("Standard AWT Click / Drag Control")
                }
            }
        }
    }
}
```
### 4. Priority Layering (Modal Overlays)

By default, both `touchScrollable` and `touchScrim` calculate their visual Z-order priority using their structural nesting layout depth (traversing up from the node to the window root). However, because foreground modal dialogs/overlays (which are often composed at the window root level) can have a shallower nesting depth than background elements (which may be deeply nested inside scaffold layouts, columns, and lists), layout depth alone is not always representative of drawing order.

To ensure foreground overlays always win the hit-test over background elements, you can pass a custom `priority` offset (default is `0`):

```kotlin
// 1. Mark the overlay root scrim with priority = 1
Box(
    modifier = Modifier
        .fillMaxSize()
        .touchScrim(priority = 1) // Elevates hit-testing priority
) {
    // 2. Mark any scrollable lists inside the overlay with priority = 1
    LazyColumn(
        state = overlayListState,
        modifier = Modifier
            .fillMaxSize()
            .touchScrollable(overlayListState, priority = 1)
    ) {
        // Items...
    }
}
```

This prevents background lists from hijacking touch interactions and ensures checkboxes, buttons, and scrollable content inside modal cards respond to touches normally.

#### Rationale & Design Trade-offs of the `priority` Parameter

While a manual `priority` parameter (analogous to CSS `z-index` or Compose's `Modifier.zIndex()`) requires developers to have pre-facto knowledge of overlapping layers, it is currently the most pragmatic and robust design for this low-level workaround:

1. **Native Interception Encapsulation**: Because this library subclasses native Win32 window procedures (`WndProc`) using JNA, touch hit-testing is performed *outside* Compose's internal event-routing system. At the OS message loop level, the library is blind to Compose's runtime drawing order.
2. **Encapsulated Compose API Constraints**: In Compose Multiplatform, public layout APIs (like `LayoutCoordinates`) do not expose child references or visual Z-order properties. Automating sibling Z-ordering without manual priority would require reflection on internal Compose classes (like `LayoutNode` or `NodeCoordinator`), which is fragile and subject to breaking change during Compose runtime updates.
3. **Decoupled Performance**: Manual priority enables fast, constant-time `O(1)` coordinate matching in JNA callbacks without tree-traversal reflection overhead.

For large-scale applications with multiple stacked modal cards, we recommend standardizing on layout constants (e.g., `priority = Layer.Modal`) rather than magic integers to keep the code maintainable.

### 5. Custom Drag-to-Scroll Helper Modifier

To combine touch scrolling (`touchScrollable`) with desktop mouse dragging
(e.g. using `draggable`), you can write a helper modifier extension:

```kotlin
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.composed
import llc.lookatwhataicando.touch.compose.touchScrollable

fun Modifier.dragToScroll(
    lazyListState: LazyListState,
    orientation: Orientation = Orientation.Vertical
): Modifier = this.composed {
    val coroutineScope = rememberCoroutineScope()
    this
        .touchScrollable(lazyListState, orientation)
        .draggable(
            orientation = orientation,
            state = rememberDraggableState { delta ->
                coroutineScope.launch {
                    lazyListState.scrollBy(-delta)
                }
            }
        )
}
```

---

## Running the Sandbox Demo App

The repository includes a `:touch-demo` subproject containing a sandbox to test
and visual-check touch scroll, fling, and click behaviors:

1. Open a terminal inside the project's root folder.
2. Run the application using the Gradle wrapper:
   ```bash
   ./gradlew :touch-demo:run
   ```
3. The demo window displays:
   * A **LazyColumn** list and a **Regular Scrollable Column** demonstrating smooth
     direct-touch scrolling.
   * A **Live WM_POINTER Event Logger** showing real-time pointer coordinates
     and touch phases (Down/Update/Up) as you contact the screen.

### Manual Verification Test Matrix

To verify the touch support behavior, run the demo on a Windows touchscreen device and execute the following manual tests:

| Test Case | Global Hook (WndProc) | Overlay Dialog | Scrim Hook | Column Hook (Lazy/Reg) | Event List Hook | Input Gesture | Target Element / Region | Expected Behavior |
|---|---|---|---|---|---|---|---|---|
| **1. Baseline OS Mode** | **Disabled** | Hidden | N/A | N/A | N/A | Touch Drag | Lazy/Regular Column | OS mouse-promotion (drag-selects text or triggers jittery drag; no inertial fling). |
| **2. Touch Scroll ON** | **Enabled** | Hidden | N/A | **Enabled** | N/A | Touch Drag | Lazy/Regular Column | Smooth 1:1 scroll tracking and kinetic momentum fling scrolling on release. |
| **3. Touch Scroll OFF** | **Enabled** | Hidden | N/A | **Disabled** | N/A | Touch Drag | Lazy/Regular Column | Custom scrolling is bypassed; component falls back to standard mouse-promotion. |
| **4. Tap-to-Click** | **Enabled** | Hidden | N/A | N/A | N/A | Touch Tap | Any list item / Card | Synthesizes a mouse click event immediately (triggers actions without scrolling). |
| **5. Overlay Scrim Block** | **Enabled** | **Shown** | **Enabled** | N/A | N/A | Touch Drag | Dialog Card / Scrim Area | Background columns do NOT scroll. Checkboxes and close buttons inside card tap natively. |
| **6. Overlay Scrim Pass** | **Enabled** | **Shown** | **Disabled** | N/A | N/A | Touch Drag | Dialog Card / Scrim Area | Background columns underneath the overlay scroll dynamically (pass-through). |
| **7. Overlay List Scroll** | **Enabled** | **Shown** | **Enabled** | N/A | **Enabled** | Touch Drag | Overlay LazyColumn | Scrollable list inside the overlay scrolls smoothly with custom momentum physics. |
| **8. Event Log Scroll** | **Enabled** | Hidden/Shown | N/A | N/A | **Always ON** | Touch Drag | Live Event Logs Column | Logs list scrolls smoothly. Scrolling this list does NOT append log entries to itself. |
| **9. Hook Toggle Lifecycle** | **Toggle Off & ON** | Hidden | N/A | **Enabled** | N/A | Touch Drag | Lazy/Regular Column | Turning the global hook off and back on leaves scrolling fully functional. |
| **10. Mouse & Scrollbar** | N/A | Hidden/Shown | N/A | N/A | N/A | Mouse Drag/Wheel | Any list or scrollbar | Traditional mouse inputs and scrollbars scroll all columns normally. |

---

## Contributing

Contributions are welcome! If you encounter any bugs, have feature requests,
or want to improve the Windows touch/gesture experience, please:
1. Open an issue describing the problem or proposal.
2. Submit a Pull Request. Ensure your changes compile successfully and follow the
   repository's code style.

## Acknowledgments & Prior Art

* [Java Native Access (JNA)](https://github.com/java-native-access/jna) for
  enabling low-level Win32 window procedure subclassing without writing custom
  C/C++ DLLs.
* The JetBrains team for their ongoing work on Compose Multiplatform.
* Prior art demonstrating native Win32 `WM_POINTER` interception and coordinate
  translation within the JVM.

## License

This project is licensed under the [MIT License](LICENSE) - see the [LICENSE](LICENSE)
file for details.
