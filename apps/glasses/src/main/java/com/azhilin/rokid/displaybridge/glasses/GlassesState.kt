package com.azhilin.rokid.displaybridge.glasses

enum class GlassesState {
    NO_CABLE,
    CHARGE_ONLY_CABLE,
    ADB_OWNS_DATA_CABLE,
    WAITING_FOR_BRIDGE,
    CONNECTING,
    PAIRING,
    READY,
    WAITING_FOR_VIDEO,
    LOADING,
    PLAYING,
    ERROR,
}

data class GlassesStatus(val title: String, val detail: String, val videoVisible: Boolean)

object GlassesStatePresentation {
    fun forState(state: GlassesState, detail: String? = null): GlassesStatus {
        val base = when (state) {
            GlassesState.NO_CABLE -> GlassesStatus("NO CABLE", "Connect the phone with a USB data cable.", false)
            GlassesState.CHARGE_ONLY_CABLE -> GlassesStatus("CHARGE-ONLY CABLE", "Use a USB data cable.", false)
            GlassesState.ADB_OWNS_DATA_CABLE -> GlassesStatus("ADB OWNS DATA CABLE", "Disable USB debugging for the AOA bridge session.", false)
            GlassesState.WAITING_FOR_BRIDGE -> GlassesStatus("WAITING FOR BRIDGE", "Open Display Bridge on the phone and tap Connect.", false)
            GlassesState.CONNECTING -> GlassesStatus("CONNECTING", "Negotiating the private DBP1 video session.", false)
            GlassesState.PAIRING -> GlassesStatus("WIRELESS PAIRING", "Establishing first-use encrypted trust.", false)
            GlassesState.READY -> GlassesStatus("READY", "Choose External Display or Screen Mirror on the phone.", false)
            GlassesState.WAITING_FOR_VIDEO -> GlassesStatus(
                "WAITING FOR VIDEO",
                "Start video in an app that supports an external display.",
                false,
            )
            GlassesState.LOADING -> GlassesStatus("LOADING", "Waiting for AVC configuration and a key frame.", true)
            GlassesState.PLAYING -> GlassesStatus("PLAYING", "", true)
            GlassesState.ERROR -> GlassesStatus("ERROR", "Check the phone diagnostics.", false)
        }
        return detail?.takeIf { it.isNotBlank() }?.let { base.copy(detail = it) } ?: base
    }
}
