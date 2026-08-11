package com.azhilin.rokid.displaybridge.companion

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import com.azhilin.rokid.displaybridge.companion.usb.UsbDeviceIdentity

class UsbAutoLaunchSettings(context: Context) {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = preferences.getBoolean(KEY_ENABLED, true)

    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).commit()
        applyComponentState(enabled)
    }

    fun synchronizeComponentState() = applyComponentState(isEnabled())

    private fun applyComponentState(enabled: Boolean) {
        applicationContext.packageManager.setComponentEnabledSetting(
            ComponentName(applicationContext, UsbAttachActivity::class.java),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    companion object {
        internal const val PREFERENCES_NAME = "display_bridge_usb_autostart_v1"
        internal const val KEY_ENABLED = "open_when_glasses_connect"
    }
}

internal fun isSupportedGlassesUsbDevice(device: UsbDeviceIdentity): Boolean {
    if (device.vendorId != RG_GLASSES_VENDOR_ID || device.productId != RG_GLASSES_PRODUCT_ID) return false
    val descriptors = listOf(device.manufacturerName, device.productName).filterNotNull()
    return descriptors.any {
        it.contains("rokid", ignoreCase = true) ||
            it.contains("rg_glasses", ignoreCase = true) ||
            it.contains("rg-glasses", ignoreCase = true)
    }
}

internal fun shouldOpenForUsbAttach(
    enabled: Boolean,
    action: String?,
    device: UsbDeviceIdentity?,
): Boolean = enabled && action == UsbManager.ACTION_USB_DEVICE_ATTACHED &&
    device != null && isSupportedGlassesUsbDevice(device)

internal const val RG_GLASSES_VENDOR_ID = 0x18d1
internal const val RG_GLASSES_PRODUCT_ID = 0x4ee7
