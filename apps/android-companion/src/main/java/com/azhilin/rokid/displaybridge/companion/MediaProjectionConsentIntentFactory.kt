package com.azhilin.rokid.displaybridge.companion

import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.annotation.RequiresApi

enum class ProjectionConsentScope { SYSTEM_CHOOSER, WHOLE_DEFAULT_DISPLAY }

object MediaProjectionConsentIntentFactory {
    fun scopeForSdk(sdkInt: Int): ProjectionConsentScope =
        if (sdkInt >= 34) ProjectionConsentScope.WHOLE_DEFAULT_DISPLAY else ProjectionConsentScope.SYSTEM_CHOOSER

    fun create(manager: MediaProjectionManager, sdkInt: Int = Build.VERSION.SDK_INT): Intent =
        if (Build.VERSION.SDK_INT >= 34 && scopeForSdk(sdkInt) == ProjectionConsentScope.WHOLE_DEFAULT_DISPLAY) {
            createWholeDefaultDisplayIntent(manager)
        } else {
            manager.createScreenCaptureIntent()
        }

    @RequiresApi(34)
    private fun createWholeDefaultDisplayIntent(manager: MediaProjectionManager): Intent =
        manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
}
