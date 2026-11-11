package com.imdadburki.cropcast.core.common

/**
 * Theme mode (spec §12, Settings → Appearance). Lives in :core:common so
 * both the design system and the DataStore can use it without a dependency
 * cycle — it is a pure enum, no Android dependency.
 */
enum class ThemeMode { SYSTEM, DARK, LIGHT }
