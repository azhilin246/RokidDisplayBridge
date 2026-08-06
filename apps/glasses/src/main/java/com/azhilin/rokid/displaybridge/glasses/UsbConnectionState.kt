package com.azhilin.rokid.displaybridge.glasses

import com.azhilin.rokid.displaybridge.protocol.LiveSourceKind

data class UsbConnectionSignals(
    val usbPowered: Boolean = false,
    val dataConnected: Boolean = false,
    val dataConfigured: Boolean = false,
    val adbSessionProven: Boolean = false,
    val accessoryPresent: Boolean = false,
)

fun idleGlassesState(signals: UsbConnectionSignals): GlassesState = when {
    signals.accessoryPresent -> GlassesState.WAITING_FOR_BRIDGE
    (signals.dataConnected || signals.dataConfigured) && signals.adbSessionProven -> GlassesState.ADB_OWNS_DATA_CABLE
    signals.dataConnected || signals.dataConfigured -> GlassesState.WAITING_FOR_BRIDGE
    signals.usbPowered -> GlassesState.CHARGE_ONLY_CABLE
    else -> GlassesState.NO_CABLE
}

class IncomingSessionGuard {
    var currentSessionId: ULong = 0u
        private set
    var currentGeneration: UInt = 0u
        private set
    private var lastSessionId: ULong = 0u

    fun resetConnection() {
        currentSessionId = 0u
        currentGeneration = 0u
        lastSessionId = 0u
    }

    fun acceptStart(sessionId: ULong, generation: UInt): Boolean {
        if (sessionId == 0uL || generation == 0u || sessionId <= lastSessionId) return false
        lastSessionId = sessionId
        currentSessionId = sessionId
        currentGeneration = generation
        return true
    }

    fun acceptConfig(sessionId: ULong, generation: UInt): Boolean {
        if (sessionId != currentSessionId || generation < currentGeneration) return false
        currentGeneration = generation
        return true
    }

    fun acceptDiscontinuity(sessionId: ULong, generation: UInt): Boolean {
        if (sessionId != currentSessionId || generation <= currentGeneration) return false
        currentGeneration = generation
        return true
    }

    fun acceptStop(sessionId: ULong): Boolean {
        if (sessionId != currentSessionId || sessionId == 0uL) return false
        currentSessionId = 0u
        currentGeneration = 0u
        return true
    }
}

class DisplayWakeGate {
    private var lastWokenSessionId: ULong = 0u

    fun resetConnection() {
        lastWokenSessionId = 0u
    }

    fun acceptFirstFrame(sessionId: ULong, sourceKind: LiveSourceKind?): Boolean {
        if (sourceKind == null || sessionId == 0uL || sessionId == lastWokenSessionId) {
            return false
        }
        lastWokenSessionId = sessionId
        return true
    }
}
