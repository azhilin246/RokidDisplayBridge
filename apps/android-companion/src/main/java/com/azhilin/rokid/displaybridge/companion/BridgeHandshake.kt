package com.azhilin.rokid.displaybridge.companion

import com.azhilin.rokid.displaybridge.protocol.DBP_MAX_PAYLOAD_SIZE
import com.azhilin.rokid.displaybridge.protocol.HelloPayload
import com.azhilin.rokid.displaybridge.protocol.LivePayloads

class BridgeHandshake {
    var complete: Boolean = false
        private set
    var maxPayload: Int = DBP_MAX_PAYLOAD_SIZE
        private set

    fun reset() {
        complete = false
        maxPayload = DBP_MAX_PAYLOAD_SIZE
    }

    fun accept(remote: HelloPayload): Int {
        require(remote.capabilities and LivePayloads.VIDEO_CAPABILITY != 0uL) {
            "Glasses do not support DBP1 video"
        }
        maxPayload = minOf(remote.maxPayloadBytes.toInt(), DBP_MAX_PAYLOAD_SIZE)
        complete = true
        return maxPayload
    }
}
