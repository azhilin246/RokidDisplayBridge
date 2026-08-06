package com.azhilin.rokid.displaybridge.companion.media

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.view.Display
import android.view.Surface
import java.io.Closeable

data class PresentationDisplayInfo(
    val displayId: Int,
    val requestedFlags: Int,
    val actualFlags: Int,
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val refreshRate: Float,
    val mode: String,
    val inPresentationCategory: Boolean,
) {
    fun diagnostic(): String = "id=$displayId requested=0x${requestedFlags.toString(16)} actual=0x${actualFlags.toString(16)} " +
        "${width}x$height density=$densityDpi refresh=${"%.2f".format(refreshRate)} mode=$mode presentation=$inPresentationCategory"
}

class PresentationDisplayController(context: Context, surface: Surface, densityDpi: Int = DENSITY_DPI) : Closeable {
    private val manager = context.getSystemService(DisplayManager::class.java)
    private val virtualDisplay: VirtualDisplay = manager.createVirtualDisplay(
        DISPLAY_NAME,
        SurfaceToAvcStream.WIDTH,
        SurfaceToAvcStream.HEIGHT,
        densityDpi,
        surface,
        REQUIRED_FLAGS,
    ) ?: error("DisplayManager returned no VirtualDisplay")

    val info: PresentationDisplayInfo = virtualDisplay.display.let { display ->
        PresentationDisplayInfo(
            displayId = display.displayId,
            requestedFlags = REQUIRED_FLAGS,
            actualFlags = display.flags,
            width = display.mode.physicalWidth,
            height = display.mode.physicalHeight,
            densityDpi = densityDpi,
            refreshRate = display.refreshRate,
            mode = display.mode.toString(),
            inPresentationCategory = manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
                .any { it.displayId == display.displayId },
        )
    }

    override fun close() = virtualDisplay.release()

    companion object {
        const val DISPLAY_NAME = "Rokid External Display"
        const val DENSITY_DPI = 320
        const val REQUIRED_FLAGS = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY

        fun hasForbiddenFlags(flags: Int): Boolean = flags and (DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE) != 0
    }
}
