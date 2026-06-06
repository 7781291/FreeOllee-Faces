package com.blizzardcaron.freeolleefaces.ui

/**
 * Formats the debug footer shown on the Home screen, e.g. "v0.6.2 · com.blizzardcaron.freeolleefaces".
 * Kept pure (no Android types) so it is unit-testable; callers pass the runtime values from
 * PackageManager. A null [versionName] renders as "?".
 */
fun versionLabel(versionName: String?, packageName: String): String =
    "v${versionName ?: "?"} · $packageName"
