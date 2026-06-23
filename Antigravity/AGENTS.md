# ComposeDesktopTouch Agent Instructions

Welcome! You are an AI coding assistant working on **ComposeDesktopTouch**—a low-level library providing native Windows touchscreen and stylus support (smooth 1:1 panning, inertial flinging, tap-to-click synthesis, and modal priority hit-testing) for Jetpack Compose for Desktop applications.

This file acts as the primary repository context and instructions guide. Read this first to align with the codebase.

---

## 📖 Documentation Maintenance Guidelines

To ensure the project context remains accurate:
1. **Synchronized Updates:** When code structures, design decisions, API signatures, or file paths change, you must update the relevant codebase documentation (including this file `AGENTS.md`, the root `README.md`, and any architectural docs).
2. **Definition of Done:** A task, refactoring, or feature implementation is not complete until all corresponding documentation has been updated to reflect the new state of the codebase.
3. **No Automatic Git Staging/Commits:** By default, never stage (`git add`) or commit (`git commit`) changes unless explicitly requested or prompted by the user.
4. **Relative Pathing Requirement:** Always write file paths relative to the folder they are in (e.g., `./README.md` or `../compose-desktop-touch/`). Never document absolute file paths or paths outside of the repository.
5. **Plan Synchronization:** Any time a CLI command, parameter, file path, or configuration flag changes or is corrected during implementation, you must immediately propagate that change to the local `implementation_plan.md` in the system app data directory, as well as any local architectural plan files.

---

## ⚙️ Core Architecture Patterns & Guidelines

Adhere to these rules when working on the codebase:

1. **Root-Level Coordinate Translation**:
   - Do not perform hit-testing using child-relative client coordinates (`ptChild`), as child windows (such as the Compose rendering canvas) may be offset from the main frame.
   - Screen coordinates must always be converted to the top-level root frame window's client space (`ptRoot`). This guarantees that all coordinates match Compose's `LayoutCoordinates.boundsInWindow()` coordinate space.

2. **DPI-Scaled, Inset-Aware Click Synthesis**:
   - Synthetic mouse clicks must be mapped from native physical pixels to logical pixels by dividing by the window's current DPI scale factor:
     `scale = window.graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0`
   - You must add window decoration offsets (`insets.left` and `insets.top`) to align the coordinates with the frame's workspace.
   - To target and trigger Compose elements properly, do not dispatch clicks to the top-level window directly. Instead, locate the deepest Swing component under the contact via `SwingUtilities.getDeepestComponentAt` and post the AWT events (`MouseEvent.MOUSE_PRESSED`, `MouseEvent.MOUSE_RELEASED`, and `MouseEvent.MOUSE_CLICKED`) directly to it via the `SystemEventQueue`.

3. **Non-Windows Safety**:
   - Keep the library compiled safely on non-Windows platforms (macOS/Linux) by executing standard no-op subclasses that return unmodified receivers or empty listeners at runtime, preventing native library compilation/loading failures on other OS platforms.

4. **Visual Layer Priority Hit-Testing**:
   - Resolve overlay conflicts (e.g. scrollable lists located underneath modal dialogs) by utilizing a visual priority elevation group parameter:
     `depth = (priority * 10000) + layoutNestingDepth`
   - Components inside overlays, dialogs, or scrims should register with `priority = 1` or higher to win coordinate hit-tests over background elements.
