package com.azhilin.rokid.displaybridge.companion

import android.app.Activity
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.azhilin.rokid.displaybridge.companion.usb.UsbDeviceIdentity

class UsbAttachActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val device = intent.usbDevice()
        val identity = device?.let {
            UsbDeviceIdentity(
                it.vendorId,
                it.productId,
                runCatching { it.manufacturerName }.getOrNull(),
                runCatching { it.productName }.getOrNull(),
            )
        }
        if (shouldOpenForUsbAttach(UsbAutoLaunchSettings(this).isEnabled(), intent.action, identity)) {
            Log.i(TAG, "verified RG_glasses attached; opening companion")
            startActivity(Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ))
        } else {
            Log.i(TAG, "USB attach ignored action=${intent.action} identity=$identity")
        }
        finish()
    }

    private fun Intent.usbDevice(): UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }

    private companion object {
        const val TAG = "RdbUsbAttach"
    }
}
