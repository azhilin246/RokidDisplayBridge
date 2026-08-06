package com.azhilin.rokid.displaybridge.companion

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.azhilin.rokid.displaybridge.companion.service.BridgeService
import com.google.android.material.color.DynamicColors

class MainActivity : AppCompatActivity() {
    private lateinit var dashboard: CompanionDashboard
    private val runtimeListener: (BridgeSnapshot) -> Unit = { snapshot -> runOnUiThread { render(snapshot) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        dashboard = CompanionDashboard(this, DisplayLayoutStore(this), CompanionDashboard.Actions(
            connect = { startBridge(BridgeService.ACTION_CONNECT) },
            disconnect = { startBridge(BridgeService.ACTION_DISCONNECT) },
            externalDisplay = { startBridge(BridgeService.ACTION_START_EXTERNAL_DISPLAY) },
            screenMirror = ::requestScreenMirror,
            stop = { startBridge(BridgeService.ACTION_STOP) },
            displayLayout = BridgeRuntime::submitDisplayLayout,
            framePlacement = {
                startActivity(Intent().setClassName(packageName, "$packageName.FramePlacementActivity"))
            },
            gestureSettings = {
                startActivity(Intent().setClassName(packageName, "$packageName.GestureSettingsActivity"))
            },
        ))
        setContentView(dashboard.root)
    }

    override fun onStart() {
        super.onStart()
        BridgeRuntime.addListener(runtimeListener)
        BridgeRuntime.setCompanionVisible(true)
        startBridge(BridgeService.ACTION_COMPANION_VISIBILITY)
    }

    override fun onStop() {
        BridgeRuntime.setCompanionVisible(false)
        BridgeRuntime.removeListener(runtimeListener)
        super.onStop()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_PROJECTION -> if (resultCode == RESULT_OK && data != null) {
                startBridge(BridgeService.ACTION_START_SCREEN_MIRROR) {
                    putExtra(BridgeService.EXTRA_PROJECTION_RESULT_CODE, resultCode)
                    putExtra(BridgeService.EXTRA_PROJECTION_DATA, data)
                }
            } else {
                BridgeRuntime.update { it.copy(error = "Screen Mirror was not started: MediaProjection consent denied or cancelled",
                    diagnostic = "Fresh MediaProjection consent is required for every session") }
            }
        }
    }

    private fun render(value: BridgeSnapshot) {
        dashboard.render(value)
    }

    private fun requestScreenMirror() {
        startBridge(BridgeService.ACTION_CANCEL_AUTO_EXTERNAL)
        val manager = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(MediaProjectionConsentIntentFactory.create(manager), REQUEST_PROJECTION)
    }

    private fun startBridge(action: String, configure: Intent.() -> Unit = {}) {
        ContextCompat.startForegroundService(this, Intent(this, BridgeService::class.java).setAction(action).apply(configure))
    }

    companion object {
        private const val REQUEST_PROJECTION = 2402
    }
}
