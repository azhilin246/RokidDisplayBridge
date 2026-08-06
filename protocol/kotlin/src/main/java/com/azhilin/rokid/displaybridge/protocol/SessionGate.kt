package com.azhilin.rokid.displaybridge.protocol

enum class PacketDisposition { ACCEPT, STALE }

class SessionGate {
    private var sessionId: ULong = 0u
    private var generation: UInt = 0u

    @Synchronized
    fun replace(sessionId: ULong, generation: UInt) {
        require(sessionId != 0uL && generation != 0u)
        this.sessionId = sessionId
        this.generation = generation
    }

    @Synchronized
    fun advanceGeneration(sessionId: ULong, generation: UInt): PacketDisposition {
        if (this.sessionId != sessionId || generation <= this.generation) return PacketDisposition.STALE
        this.generation = generation
        return PacketDisposition.ACCEPT
    }

    @Synchronized fun classify(sessionId: ULong, generation: UInt): PacketDisposition =
        if (this.sessionId == sessionId && this.generation == generation) PacketDisposition.ACCEPT else PacketDisposition.STALE

    @Synchronized fun end(sessionId: ULong) {
        if (this.sessionId == sessionId) { this.sessionId = 0u; generation = 0u }
    }

    @Synchronized fun clear() { sessionId = 0u; generation = 0u }
}
