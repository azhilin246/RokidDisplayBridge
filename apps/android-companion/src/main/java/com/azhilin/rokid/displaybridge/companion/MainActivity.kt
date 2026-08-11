package com.azhilin.rokid.displaybridge.companion

import android.app.Activity.RESULT_OK
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.azhilin.rokid.displaybridge.companion.service.BridgeService
import com.google.android.material.color.DynamicColors

class MainActivity : AppCompatActivity() {
    private lateinit var dashboard: CompanionDashboard
    private lateinit var usbAutoLaunchSettings: UsbAutoLaunchSettings
    private lateinit var preferredOutputModeStore: PreferredOutputModeStore
    private var pendingWirelessAction: (() -> Unit)? = null
    private var previousReady = false
    private var projectionRequestPending = false
    private val nearbyPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val action = pendingWirelessAction.also { pendingWirelessAction = null }
        if (results.values.all { it }) action?.invoke() else BridgeRuntime.update {
            it.copy(
                connection = ConnectionState.ERROR,
                connectionDetail = "Nearby devices permission denied",
                wirelessStage = "permission denied",
                error = "Wireless mode needs Nearby devices permission",
            )
        }
    }
    private val runtimeListener: (BridgeSnapshot) -> Unit = { snapshot ->
        runOnUiThread {
            render(snapshot)
            applyPreferredOutputAfterConnect(snapshot)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        usbAutoLaunchSettings = UsbAutoLaunchSettings(this).also { it.synchronizeComponentState() }
        preferredOutputModeStore = PreferredOutputModeStore(this)
        dashboard = CompanionDashboard(
            this,
            DisplayLayoutStore(this),
            CompanionDashboard.Actions(
                connect = { startBridge(BridgeService.ACTION_CONNECT) },
                disconnect = { startBridge(BridgeService.ACTION_DISCONNECT) },
                externalDisplay = { chooseOutputMode(PreferredOutputMode.EXTERNAL_DISPLAY) },
                screenMirror = { chooseOutputMode(PreferredOutputMode.SCREEN_MIRROR) },
                stop = { startBridge(BridgeService.ACTION_STOP) },
                displayLayout = BridgeRuntime::submitDisplayLayout,
                framePlacement = {
                    startActivity(Intent().setClassName(packageName, "$packageName.FramePlacementActivity"))
                },
                gestureSettings = {
                    startActivity(Intent().setClassName(packageName, "$packageName.GestureSettingsActivity"))
                },
                usbAutoLaunchChanged = usbAutoLaunchSettings::setEnabled,
                pairWireless = { withWirelessPermission { startBridge(BridgeService.ACTION_PAIR_WIRELESS) } },
                connectWireless = { withWirelessPermission { startBridge(BridgeService.ACTION_CONNECT_WIRELESS) } },
                forgetWireless = { startBridge(BridgeService.ACTION_FORGET_WIRELESS) },
            ),
            usbAutoLaunchEnabled = usbAutoLaunchSettings.isEnabled(),
            preferredOutputMode = preferredOutputModeStore.load(),
        )
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
            REQUEST_PROJECTION -> {
                projectionRequestPending = false
                if (resultCode == RESULT_OK && data != null) {
                    startBridge(BridgeService.ACTION_START_SCREEN_MIRROR) {
                        putExtra(BridgeService.EXTRA_PROJECTION_RESULT_CODE, resultCode)
                        putExtra(BridgeService.EXTRA_PROJECTION_DATA, data)
                    }
                } else {
                    BridgeRuntime.update { it.copy(
                        error = "Screen Mirror permission was cancelled",
                        diagnostic = "Screen Mirror permission was cancelled",
                    ) }
                }
            }
        }
    }

    private fun render(value: BridgeSnapshot) {
        dashboard.render(value)
    }

    private fun chooseOutputMode(mode: PreferredOutputMode) {
        preferredOutputModeStore.save(mode)
        dashboard.setPreferredOutputMode(mode)
        startBridge(BridgeService.ACTION_PREFERRED_OUTPUT_CHANGED)
        val snapshot = BridgeRuntime.snapshot()
        if (snapshot.connection != ConnectionState.READY || snapshot.mode == mode.bridgeMode) return
        when (mode) {
            PreferredOutputMode.EXTERNAL_DISPLAY -> startBridge(BridgeService.ACTION_START_EXTERNAL_DISPLAY)
            PreferredOutputMode.SCREEN_MIRROR -> requestScreenMirror()
        }
    }

    private fun applyPreferredOutputAfterConnect(snapshot: BridgeSnapshot) {
        val wasReady = previousReady
        previousReady = snapshot.connection == ConnectionState.READY
        if (projectionRequestPending) return
        if (shouldRequestPreferredScreenMirror(wasReady, snapshot, preferredOutputModeStore.load())) {
            requestScreenMirror()
        }
    }

    private fun requestScreenMirror() {
        if (projectionRequestPending) return
        projectionRequestPending = true
        startBridge(BridgeService.ACTION_CANCEL_AUTO_EXTERNAL)
        val manager = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(MediaProjectionConsentIntentFactory.create(manager), REQUEST_PROJECTION)
    }

    private fun startBridge(action: String, configure: Intent.() -> Unit = {}) {
        ContextCompat.startForegroundService(this, Intent(this, BridgeService::class.java).setAction(action).apply(configure))
    }

    private fun withWirelessPermission(action: () -> Unit) {
        val required = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (required.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            action()
        } else {
            pendingWirelessAction = action
            nearbyPermission.launch(required)
        }
    }

    companion object {
        private const val REQUEST_PROJECTION = 2402
    }
}
