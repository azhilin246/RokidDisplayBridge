package com.azhilin.rokid.displaybridge.companion

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch

internal class CompanionDashboard(
    private val activity: Activity,
    private val layoutStore: DisplayLayoutStore,
    private val actions: Actions,
    usbAutoLaunchEnabled: Boolean = true,
    preferredOutputMode: PreferredOutputMode = PreferredOutputMode.EXTERNAL_DISPLAY,
) {
    data class Actions(
        val connect: () -> Unit,
        val externalDisplay: () -> Unit,
        val screenMirror: () -> Unit,
        val stop: () -> Unit,
        val displayLayout: (com.azhilin.rokid.displaybridge.protocol.DisplayLayoutPayload) -> Unit,
        val disconnect: () -> Unit = {},
        val framePlacement: () -> Unit = {},
        val gestureSettings: () -> Unit = {},
        val usbAutoLaunchChanged: (Boolean) -> Unit = {},
        val pairWireless: () -> Unit = {},
        val connectWireless: () -> Unit = {},
        val forgetWireless: () -> Unit = {},
    )

    val root: DrawerLayout

    private val connectionChip = Chip(activity)
    private val connectionTitle = TextView(activity)
    private val connectionDetail = TextView(activity)
    private lateinit var connectionButton: MaterialButton
    private lateinit var connectionModeGroup: MaterialButtonToggleGroup
    private lateinit var wiredButton: MaterialButton
    private lateinit var wirelessButton: MaterialButton
    private lateinit var wiredSettings: View
    private lateinit var wirelessSettings: View
    private lateinit var wirelessPairButton: MaterialButton
    private lateinit var wirelessForgetButton: MaterialButton
    private val externalButton: MaterialButton
    private val mirrorButton: MaterialButton
    private val errorCard: MaterialCardView
    private val errorText = TextView(activity)
    private var selectedConnectionMode = ConnectionMode.WIRED
    private var preferredOutputMode = preferredOutputMode
    private var latestSnapshot = BridgeSnapshot()
    private val primary = color(androidx.appcompat.R.attr.colorPrimary, 0xFF536BAF.toInt())
    private val primaryContainer = color(com.google.android.material.R.attr.colorPrimaryContainer, 0xFFDCE1FF.toInt())
    private val onPrimaryContainer = color(com.google.android.material.R.attr.colorOnPrimaryContainer, 0xFF0A1B4B.toInt())
    private val onPrimary = color(com.google.android.material.R.attr.colorOnPrimary, Color.WHITE)
    private val surface = color(com.google.android.material.R.attr.colorSurface, 0xFFFBF8FF.toInt())
    private val surfaceContainer = color(com.google.android.material.R.attr.colorSurfaceContainer, 0xFFF0EDF5.toInt())
    private val onSurface = color(com.google.android.material.R.attr.colorOnSurface, 0xFF1B1B20.toInt())
    private val onSurfaceVariant = color(com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF45464F.toInt())
    private val outline = color(com.google.android.material.R.attr.colorOutline, 0xFF757680.toInt())
    private val error = color(android.R.attr.colorError, 0xFFBA1A1A.toInt())
    private val onError = color(com.google.android.material.R.attr.colorOnError, Color.WHITE)
    private val errorContainer = color(com.google.android.material.R.attr.colorErrorContainer, 0xFFFFDAD6.toInt())
    private val onErrorContainer = color(com.google.android.material.R.attr.colorOnErrorContainer, 0xFF410002.toInt())

    init {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(40))
            setBackgroundColor(surface)
        }

        content.addView(sectionTitle("Connection"), margin(top = 14, bottom = 10))
        content.addView(connectionCard(usbAutoLaunchEnabled))

        content.addView(sectionTitle("Output mode"), margin(top = 26, bottom = 10))
        externalButton = modeButton(
            "External display",
            "Use as an Android external display",
            R.drawable.ic_external_display,
            actions.externalDisplay,
        )
        mirrorButton = modeButton(
            "Screen mirror",
            "Mirror the phone screen",
            R.drawable.ic_screen_mirror,
            actions.screenMirror,
        )
        content.addView(externalButton, margin(bottom = 10))
        content.addView(mirrorButton)

        errorCard = card(errorContainer).apply {
            id = R.id.error_card
            visibility = View.GONE
            strokeColor = error
            strokeWidth = dp(1)
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(16), dp(18), dp(16))
                addView(TextView(activity).apply {
                    text = "Needs attention"
                    setTextColor(onErrorContainer)
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                })
                addView(errorText.apply {
                    setTextColor(onErrorContainer)
                    textSize = 16f
                    setPadding(0, dp(5), 0, 0)
                })
            })
        }
        content.addView(errorCard, margin(top = 16))

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            setBackgroundColor(surface)
            addView(content)
        }
        root = companionPage(activity, CompanionPage.HOME, "Display Bridge", scroll, R.id.header_title).root
    }

    fun render(value: BridgeSnapshot) {
        latestSnapshot = value
        connectionChip.text = value.connection.displayName()
        connectionTitle.text = when (value.connection) {
            ConnectionState.READY -> "Glasses connected"
            ConnectionState.ERROR -> "Connection needs attention"
            ConnectionState.DISCONNECTED -> "Waiting for glasses"
            else -> "Connecting to glasses"
        }
        connectionDetail.text = when (value.connection) {
            ConnectionState.READY -> if (value.transport.startsWith("Wi-Fi")) "Wireless" else "Wired"
            ConnectionState.DISCONNECTED -> "Choose Wired or Wireless"
            ConnectionState.USB_PERMISSION -> value.connectionDetail
            ConnectionState.ERROR -> value.error ?: value.connectionDetail
            else -> "Connecting…"
        }
        val healthy = value.connection == ConnectionState.READY
        connectionChip.chipBackgroundColor = ColorStateList.valueOf(if (healthy) primaryContainer else surface)
        connectionChip.setTextColor(if (healthy) onPrimaryContainer else onSurfaceVariant)

        val displayedMode = value.mode.takeUnless { it == BridgeMode.IDLE } ?: preferredOutputMode.bridgeMode
        styleModeButton(externalButton, displayedMode == BridgeMode.EXTERNAL_DISPLAY)
        styleModeButton(mirrorButton, displayedMode == BridgeMode.SCREEN_MIRROR)
        if (value.connection in ACTIVE_CONNECTION_STATES) {
            if (value.transport.startsWith("Wi-Fi")) selectConnectionMode(ConnectionMode.WIRELESS)
            else if (value.transport.startsWith("USB")) selectConnectionMode(ConnectionMode.WIRED)
        }
        updateConnectionControls(value)

        errorCard.visibility = if (value.error == null) View.GONE else View.VISIBLE
        errorText.text = value.error.orEmpty()
    }

    fun setPreferredOutputMode(mode: PreferredOutputMode) {
        preferredOutputMode = mode
        val displayedMode = latestSnapshot.mode.takeUnless { it == BridgeMode.IDLE } ?: mode.bridgeMode
        styleModeButton(externalButton, displayedMode == BridgeMode.EXTERNAL_DISPLAY)
        styleModeButton(mirrorButton, displayedMode == BridgeMode.SCREEN_MIRROR)
    }

    private fun connectionCard(usbAutoLaunchEnabled: Boolean) = card(surfaceContainer).apply {
        id = R.id.connection_card
        strokeColor = outline
        strokeWidth = dp(1)
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            addView(connectionChip.apply {
                id = R.id.connection_status
                isClickable = false
                isCheckable = false
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(connectionTitle.apply {
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(onSurface)
                setPadding(0, dp(10), 0, 0)
            })
            addView(connectionDetail.apply {
                textSize = 14f
                setTextColor(onSurfaceVariant)
                alpha = .82f
                setPadding(0, dp(4), 0, dp(10))
            })
            connectionModeGroup = MaterialButtonToggleGroup(activity).apply {
                id = R.id.connection_mode_group
                isSingleSelection = true
                isSelectionRequired = true
                wiredButton = connectionModeButton("Wired", R.id.connection_wired_action)
                wirelessButton = connectionModeButton("Wireless", R.id.connection_wireless_action)
                addView(wiredButton, LinearLayout.LayoutParams(0, dp(48), 1f))
                addView(wirelessButton, LinearLayout.LayoutParams(0, dp(48), 1f))
                check(R.id.connection_wired_action)
                addOnButtonCheckedListener { _, checkedId, isChecked ->
                    if (isChecked) {
                        selectedConnectionMode = if (checkedId == R.id.connection_wireless_action) {
                            ConnectionMode.WIRELESS
                        } else ConnectionMode.WIRED
                        updateConnectionControls(latestSnapshot)
                    }
                }
            }
            addView(connectionModeGroup, margin(bottom = 10))
            connectionButton = MaterialButton(activity).apply {
                id = R.id.connection_action
                text = "Connect"
                isAllCaps = false
                minHeight = dp(48)
                backgroundTintList = ColorStateList.valueOf(primary)
                setTextColor(onPrimary)
            }
            addView(connectionButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            wiredSettings = MaterialSwitch(activity).apply {
                id = R.id.usb_autostart_toggle
                text = "Open when glasses connect"
                isChecked = usbAutoLaunchEnabled
                setPadding(0, dp(10), 0, 0)
                setOnCheckedChangeListener { _, checked -> actions.usbAutoLaunchChanged(checked) }
            }
            addView(wiredSettings)
            wirelessSettings = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                visibility = View.GONE
                wirelessPairButton = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    id = R.id.wireless_pair_action
                    text = "Pair new"
                    isAllCaps = false
                    backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                    strokeColor = ColorStateList.valueOf(primary)
                    strokeWidth = dp(1)
                    setTextColor(primary)
                    setOnClickListener { actions.pairWireless() }
                }
                addView(wirelessPairButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                wirelessForgetButton = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    id = R.id.wireless_forget_action
                    text = "Forget"
                    isAllCaps = false
                    backgroundTintList = ColorStateList.valueOf(errorContainer)
                    strokeColor = ColorStateList.valueOf(this@CompanionDashboard.error)
                    strokeWidth = dp(1)
                    setTextColor(onErrorContainer)
                    setOnClickListener { actions.forgetWireless() }
                }
                addView(wirelessForgetButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(8)
                })
            }
            addView(wirelessSettings, margin(top = 10))
        })
    }

    private fun connectionModeButton(label: String, viewId: Int) =
        MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            id = viewId
            text = label
            isAllCaps = false
            isCheckable = true
        }

    private fun selectConnectionMode(mode: ConnectionMode) {
        if (selectedConnectionMode == mode) return
        selectedConnectionMode = mode
        connectionModeGroup.check(if (mode == ConnectionMode.WIRELESS) R.id.connection_wireless_action else R.id.connection_wired_action)
    }

    private fun updateConnectionControls(value: BridgeSnapshot) {
        val connected = value.connection == ConnectionState.READY || value.connection == ConnectionState.HANDSHAKING ||
            (value.connection == ConnectionState.ERROR && value.handshake.startsWith("DBP1 ready"))
        connectionModeGroup.isEnabled = true
        wiredButton.isEnabled = true
        wirelessButton.isEnabled = true
        wiredButton.isClickable = !connected
        wirelessButton.isClickable = !connected
        wiredButton.isFocusable = !connected
        wirelessButton.isFocusable = !connected
        wiredSettings.visibility = if (selectedConnectionMode == ConnectionMode.WIRED) View.VISIBLE else View.GONE
        wirelessSettings.visibility =
            if (selectedConnectionMode == ConnectionMode.WIRELESS && !connected) View.VISIBLE else View.GONE
        wirelessPairButton.visibility = if (value.wirelessPaired) View.GONE else View.VISIBLE
        wirelessForgetButton.visibility = if (value.wirelessPaired) View.VISIBLE else View.GONE
        wirelessPairButton.isEnabled = true
        wirelessForgetButton.isEnabled = true
        styleConnectionModeButton(wiredButton, selectedConnectionMode == ConnectionMode.WIRED)
        styleConnectionModeButton(wirelessButton, selectedConnectionMode == ConnectionMode.WIRELESS)
        connectionButton.text = when {
            connected -> "Disconnect"
            selectedConnectionMode == ConnectionMode.WIRELESS && !value.wirelessPaired -> "Pair"
            else -> "Connect"
        }
        connectionButton.backgroundTintList = ColorStateList.valueOf(if (connected) error else primary)
        connectionButton.setTextColor(if (connected) onError else onPrimary)
        connectionButton.setOnClickListener {
            when {
                connected -> actions.disconnect()
                selectedConnectionMode == ConnectionMode.WIRELESS && !value.wirelessPaired -> actions.pairWireless()
                selectedConnectionMode == ConnectionMode.WIRELESS -> actions.connectWireless()
                else -> actions.connect()
            }
        }
    }

    private fun styleConnectionModeButton(button: MaterialButton, selected: Boolean) {
        button.backgroundTintList = ColorStateList.valueOf(if (selected) primary else Color.TRANSPARENT)
        button.setTextColor(if (selected) onPrimary else onSurface)
        button.strokeColor = ColorStateList.valueOf(if (selected) primary else outline)
        button.strokeWidth = dp(if (selected) 2 else 1)
        button.alpha = 1f
    }

    private fun modeButton(title: String, supporting: String, iconRes: Int, action: () -> Unit) =
        MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "$title\n$supporting"
            textSize = 15f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            isAllCaps = false
            minHeight = dp(82)
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(22)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setIconResource(iconRes)
            iconSize = dp(28)
            iconPadding = dp(14)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            setOnClickListener { action() }
            id = if (title.startsWith("External")) R.id.mode_external else R.id.mode_mirror
        }

    private fun styleModeButton(button: MaterialButton, selected: Boolean) {
        button.backgroundTintList = ColorStateList.valueOf(if (selected) primary else surfaceContainer)
        button.strokeColor = ColorStateList.valueOf(if (selected) primary else outline)
        button.strokeWidth = dp(if (selected) 2 else 1)
        button.setTextColor(if (selected) onPrimary else onSurface)
        button.iconTint = ColorStateList.valueOf(if (selected) onPrimary else onSurfaceVariant)
    }

    private fun sectionTitle(value: String) = TextView(activity).apply {
        text = value
        textSize = 19f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(onSurface)
    }

    private fun body(value: String) = TextView(activity).apply {
        text = value
        textSize = 14f
        setTextColor(onSurfaceVariant)
    }

    private fun card(background: Int) = MaterialCardView(activity).apply {
        radius = dp(24).toFloat()
        cardElevation = 0f
        setCardBackgroundColor(background)
        strokeColor = Color.TRANSPARENT
        strokeWidth = 0
    }

    private fun margin(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    private fun color(attr: Int, fallback: Int) = MaterialColors.getColor(activity, attr, fallback)
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    private enum class ConnectionMode { WIRED, WIRELESS }

    private companion object {
        val ACTIVE_CONNECTION_STATES = setOf(
            ConnectionState.USB_PERMISSION,
            ConnectionState.SWITCHING_TO_AOA,
            ConnectionState.HANDSHAKING,
            ConnectionState.READY,
        )
    }

}

internal fun ConnectionState.displayName() = name.replace('_', ' ')
internal fun BridgeMode.displayName() = when (this) {
    BridgeMode.IDLE -> "IDLE"
    BridgeMode.EXTERNAL_DISPLAY -> "EXTERNAL DISPLAY"
    BridgeMode.SCREEN_MIRROR -> "SCREEN MIRROR"
}

internal fun BridgeSnapshot.technicalText() = buildString {
    appendLine("Connection : $connection — $connectionDetail")
    appendLine("Transport  : $transport")
    appendLine("Wireless   : $wirelessStage · paired=$wirelessPaired")
    appendLine("AOA identity: $aoaIdentity")
    appendLine("Handshake  : $handshake")
    appendLine("Mode       : $mode")
    appendLine("Display    : $display")
    appendLine("Encoder    : $encoder")
    appendLine("Audio route: $audioRoute")
    appendLine("Thermal    : $thermalStatus")
    appendLine("Rate       : $frameRate fps · ${bitrate / 1_000} kbps")
    appendLine("Frames     : sent=$sentFrames decoded=$decodedFrames dropped=$droppedFrames")
    appendLine("Queue      : $queueDepth")
    appendLine("Frame      : $displayLayout")
    appendLine("Media key  : $lastMediaControl")
    append("Diagnostic : $diagnostic")
}
