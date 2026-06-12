package llc.lookatwhataicando.composedesktoptouch

/**
 * Single OS gate for the whole library. Intentionally trivial (no dependency
 * on host-app utilities): every public API checks this and degrades to a
 * no-op off Windows.
 */
internal val internalIsWindows: Boolean =
    System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
