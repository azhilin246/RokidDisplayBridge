package com.azhilin.rokid.displaybridge.protocol

import java.io.Closeable

interface DbpTransport : Closeable {
    val isOpen: Boolean
    val transportName: String
    fun send(frame: Frame, completion: ((Boolean) -> Unit)? = null)

    interface Listener {
        fun onFrame(frame: Frame)
        fun onDisconnected(message: String)
        fun onError(message: String)
    }
}
