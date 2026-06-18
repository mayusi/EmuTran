package io.github.mayusi.emutran.ui.common

/**
 * Shared UI-layer constants.
 *
 * [DOWNLOAD_EMIT_THROTTLE_MS] is the minimum interval between Downloading-state
 * emissions during a self-update. ViewModels throttle Chunk→Downloading updates
 * to this window so every 64 KB chunk doesn't re-compose the sheet (which
 * re-parses markdown). Shared by DashboardViewModel and AboutViewModel — keep
 * this the single source of truth so both stay in lock-step.
 */
const val DOWNLOAD_EMIT_THROTTLE_MS = 200L
