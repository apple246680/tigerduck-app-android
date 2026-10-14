package org.ntust.app.tigerduck.util

/**
 * Substitute the iOS-style positional placeholder `%1$@` (or `%2$@`, etc.) with
 * an Android-side value. The localization submodule's grouped-source schema is
 * iOS-first and emits `%@` placeholders verbatim into the Android strings; Java
 * formatting wants `%s`, so a runtime `replace` is the lightest cross-platform
 * shim until the submodule grows an Android-specific formatter.
 *
 * Centralized here (instead of inlined at the call site) so a third surface
 * that hits the same shape can reuse this rather than re-implementing the
 * escape — and so the right fix (submodule emits `%s` directly) has exactly
 * one chase point to delete.
 */
internal fun String.replaceIosArg(arg: Int, value: String): String =
    replace("%$arg\$@", value)

/**
 * [replaceIosArg] for a whole number, which the iOS-first strings write as
 * `%1$lld` (`a11y_course_color_swatch`, `a11y_calendar_day`). Java formatting
 * has no `ll` length, so `getString(id, n)` would throw on these.
 */
internal fun String.replaceIosArg(arg: Int, value: Long): String =
    replace("%$arg\$lld", value.toString()).replace("%$arg\$d", value.toString())
