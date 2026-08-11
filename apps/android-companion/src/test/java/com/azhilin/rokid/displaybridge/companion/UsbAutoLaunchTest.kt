package com.azhilin.rokid.displaybridge.companion

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.azhilin.rokid.displaybridge.companion.usb.UsbDeviceIdentity
import com.google.android.material.materialswitch.MaterialSwitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UsbAutoLaunchTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun `setting defaults enabled persists and controls only attach dispatcher`() {
        context.getSharedPreferences(UsbAutoLaunchSettings.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        val settings = UsbAutoLaunchSettings(context)
        val component = ComponentName(context, UsbAttachActivity::class.java)

        assertTrue(settings.isEnabled())
        settings.setEnabled(false)
        assertFalse(UsbAutoLaunchSettings(context).isEnabled())
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            context.packageManager.getComponentEnabledSetting(component))
        settings.setEnabled(true)
        assertTrue(UsbAutoLaunchSettings(context).isEnabled())
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            context.packageManager.getComponentEnabledSetting(component))
    }

    @Test fun `attach matching is exact and fail closed`() {
        val glasses = UsbDeviceIdentity(0x18d1, 0x4ee7, "Rokid", "RG_glasses")
        assertTrue(shouldOpenForUsbAttach(true, UsbManager.ACTION_USB_DEVICE_ATTACHED, glasses))
        assertTrue(isSupportedGlassesUsbDevice(glasses.copy(manufacturerName = null)))
        assertFalse(shouldOpenForUsbAttach(false, UsbManager.ACTION_USB_DEVICE_ATTACHED, glasses))
        assertFalse(shouldOpenForUsbAttach(true, Intent.ACTION_VIEW, glasses))
        assertFalse(shouldOpenForUsbAttach(true, UsbManager.ACTION_USB_DEVICE_ATTACHED, null))
        assertFalse(isSupportedGlassesUsbDevice(glasses.copy(productId = 0x2d00)))
        assertFalse(isSupportedGlassesUsbDevice(glasses.copy(manufacturerName = null, productName = null)))
        assertFalse(isSupportedGlassesUsbDevice(UsbDeviceIdentity(0x05ac, 0x1463, "Apple", "USB-C AV")))
    }

    @Test fun `manifest dispatcher and native USB filter are narrowly declared`() {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_ACTIVITIES)
        val dispatcher = requireNotNull(info.activities).single { it.name.endsWith("UsbAttachActivity") }
        assertTrue(dispatcher.exported)

        val parser = context.resources.getXml(R.xml.usb_device_filter)
        while (parser.eventType != org.xmlpull.v1.XmlPullParser.START_TAG || parser.name != "usb-device") parser.next()
        assertEquals("6353", parser.getAttributeValue(null, "vendor-id"))
        assertEquals("20199", parser.getAttributeValue(null, "product-id"))
    }

    @Test fun `dashboard exposes persisted state and reports toggle changes`() {
        val controller = Robolectric.buildActivity(android.app.Activity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        val activity = controller.setup().get()
        var changed: Boolean? = null
        val dashboard = CompanionDashboard(
            activity,
            DisplayLayoutStore(activity),
            CompanionDashboard.Actions({}, {}, {}, {}, {}, usbAutoLaunchChanged = { changed = it }),
            usbAutoLaunchEnabled = false,
        )
        val toggle = dashboard.root.findViewById<MaterialSwitch>(R.id.usb_autostart_toggle)
        assertFalse(toggle.isChecked)
        toggle.performClick()
        assertEquals(true, changed)
        assertTrue(dashboard.root.findViewById<View>(R.id.connection_card) != null)
    }
}
