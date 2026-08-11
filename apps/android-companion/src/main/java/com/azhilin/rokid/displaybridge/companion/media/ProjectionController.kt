package com.azhilin.rokid.displaybridge.companion.media

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

class ProjectionController(
    private val projection: MediaProjection,
    surface: Surface,
    private val onProjectionStopped: () -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection callback onStop")
            if (!closed.get()) onProjectionStopped()
        }
    }
    private val display: VirtualDisplay

    init {
        projection.registerCallback(callback, Handler(Looper.getMainLooper()))
        display = projection.createVirtualDisplay(
            "Rokid Screen Mirror Bridge",
            SurfaceToAvcStream.WIDTH,
            SurfaceToAvcStream.HEIGHT,
            PresentationDisplayController.DENSITY_DPI,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null,
        ) ?: error("MediaProjection returned no VirtualDisplay")
    }

    fun replaceSurface(surface: Surface?) {
        check(!closed.get()) { "MediaProjection display is closed" }
        display.surface = surface
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { display.release() }
        runCatching { projection.unregisterCallback(callback) }
        runCatching { projection.stop() }
    }

    companion object { private const val TAG = "RdbProjection" }
}
