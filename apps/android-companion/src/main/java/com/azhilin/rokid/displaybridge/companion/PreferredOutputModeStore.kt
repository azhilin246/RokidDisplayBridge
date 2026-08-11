package com.azhilin.rokid.displaybridge.companion

import android.content.Context

enum class PreferredOutputMode {
    EXTERNAL_DISPLAY,
    SCREEN_MIRROR;

    val bridgeMode: BridgeMode
        get() = when (this) {
            EXTERNAL_DISPLAY -> BridgeMode.EXTERNAL_DISPLAY
            SCREEN_MIRROR -> BridgeMode.SCREEN_MIRROR
        }
}

class PreferredOutputModeStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): PreferredOutputMode = preferences.getString(KEY_MODE, null)
        ?.let { runCatching { PreferredOutputMode.valueOf(it) }.getOrNull() }
        ?: PreferredOutputMode.EXTERNAL_DISPLAY

    fun save(mode: PreferredOutputMode) {
        preferences.edit().putString(KEY_MODE, mode.name).commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "display_bridge_output_mode_v1"
        const val KEY_MODE = "preferred_output_mode"
    }
}

internal fun shouldRequestPreferredScreenMirror(
    wasReady: Boolean,
    snapshot: BridgeSnapshot,
    preferred: PreferredOutputMode,
): Boolean = !wasReady &&
    snapshot.connection == ConnectionState.READY &&
    snapshot.mode == BridgeMode.IDLE &&
    preferred == PreferredOutputMode.SCREEN_MIRROR
