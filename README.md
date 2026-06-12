# Compose Desktop Touch Support for Windows

This repository contains implementations designed to add native Windows
touchscreen and stylus support (smooth 1:1 panning, inertial flinging,
click synthesis, and modal priority hit-testing) to Jetpack/JetBrains
Compose for Desktop applications.

---

## Repository Structure

The repository is divided into two separate implementations:

### 1. [Antigravity](./Antigravity) (Active & Fully Functional)
* **Status**: ✅ **Production-Ready & Fully Functional**
* **Author**: Antigravity 2.0 (using the Gemini 3.5 Flash (Hard) AI model)
* **Details**: 
  - Implements low-level Win32 `WndProc` subclassing using JNA on the top-level
    window and child canvas HWNDs.
  - Supports organic **momentum fling scrolling** using Compose's native
    velocity trackers and animation curves.
  - Provides **tap-to-click propagation** to ensure buttons, switches, and
    other standard controls trigger normally.
  - Fixes **overlap z-order depth conflicts** via a priority-layered
    `Modifier.touchScrim` and `Modifier.touchScrollable` configuration
    (ensuring foreground modal dialogs receive touch inputs correctly while
    blocking underlying scroll lists).
  - Includes a fully automated unit test suite and a high-contrast
    dark-themed sandbox demonstration app.
  - For configuration and publishing guides, see the
    [Antigravity README](./Antigravity/README.md).

### 2. [Fable](./Fable) (WIP & Incomplete)
* **Status**: ❌ **Failed / Non-functional / Incomplete**
* **Author**: Claude Code (using the Fable AI model)
* **Details**:
  - An incomplete implementation that was left in a non-functional state.
  - Stored here for reference or historical comparison.

---

## Getting Started

To explore or run the active touch implementation:
1. Open a terminal in the `./Antigravity` directory.
2. Read the [Antigravity Documentation](./Antigravity/README.md) for setup and API
   guidelines.
3. Launch the sandbox demonstration:
   ```bash
   ./gradlew :touch-demo:run
   ```
