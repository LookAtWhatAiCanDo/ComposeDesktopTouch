# compose-desktop-touch

Windows touchscreen support for [Compose for Desktop](https://www.jetbrains.com/compose-multiplatform/).

## The problem

Compose for Desktop renders inside an AWT window, and AWT has no touch API. On
Windows, the OS "mouse-promotes" touchscreen pan gestures into synthesized,
discrete `WM_MOUSEWHEEL` ticks — no contact tracking, no velocity, no 1:1 drag,
no fling. JetBrains has shipped no desktop touch support through Compose
Multiplatform 1.11 ([compose-jb#1555](https://github.com/JetBrains/compose-multiplatform/issues/1555)).

This library subclasses the native window via JNA and handles `WM_POINTER`
touch messages itself. When a window proc handles `WM_POINTERDOWN/UPDATE/UP`
instead of passing them to `DefWindowProc`, Windows does **not** mouse-promote
them — the library gets real per-contact positions and drives Compose
`ScrollableState` with true 1:1 drag, velocity-tracked momentum fling, and tap
synthesis.

## Usage

```kotlin
Window(onCloseRequest = ::exitApplication) {
    LaunchedEffect(Unit) { WindowsTouch.install(window) }   // no-op off Windows

    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier.touchScrollable(listState),     // opt this container in
    ) { /* items */ }
}
```

That's it — `install` once per window, `Modifier.touchScrollable(state)` per
scrollable container. Works with any `ScrollableState` (`ScrollState`,
`LazyListState`, `LazyGridState`, …); `orientation` and a custom `flingDecay`
are optional parameters.

## Behavior

- **Drag** tracks the finger 1:1 (px-for-px).
- **Release** computes velocity (Compose `VelocityTracker`) and flings with
  `splineBasedDecay` (Android-feel); touching down again cancels the fling.
- **Taps** (≤ ~10 px, < 300 ms) are re-synthesized as AWT clicks, so clickable
  rows inside a touch-scrollable list still work.
- **Everything else passes through.** Mouse and pen input are untouched; touch
  contacts that land *outside* any `touchScrollable` region are left to the OS
  and promote to normal mouse clicks/wheel exactly as before.
- **Non-Windows:** every API is a guarded no-op — no platform branching needed
  in consumer code.
- **Multi-window:** safe; regions are keyed per window, and the subclass is
  removed on window dispose (`WM_NCDESTROY`-safe).

## Limitations

- **Registry approach:** only containers annotated with `touchScrollable`
  respond to touch. Touch is not synthesized into the Compose scene as generic
  pointer events, so e.g. swipe gestures, text selection, sliders, or
  `detectDragGestures` users do not see touch input. Full pointer-event
  synthesis into `ComposeScene` is noted as future work.
- Single-contact gestures only: the first finger scrolls; additional
  simultaneous contacts are ignored (no pinch-zoom).
- Window association of regions uses Compose's internal `LocalWindow` via
  reflection (public API absent as of CMP 1.11). If a future Compose version
  removes it, the library degrades gracefully: hit-testing still works, but
  apps with several overlapping touch-enabled windows may mis-attribute
  regions.
- Windows 8+ only (`WM_POINTER` API); on Windows 7 install fails soft
  (`TouchInstallation.isActive == false`) and OS default behavior remains.

## Module layout

- `win32` package — JNA mappings and window subclassing. No Compose imports;
  emits plain `TouchEvent(id, phase, x, y, time)` values from the native
  message pump.
- `compose` package — the adapter: `WindowsTouch.install`,
  `Modifier.touchScrollable`, gesture state machine (drag/fling/tap).

## Publishing

```
./gradlew :compose-desktop-touch:publishToMavenLocal
```

Coordinates: `llc.lookatwhataicando:compose-desktop-touch:0.1.0`.
