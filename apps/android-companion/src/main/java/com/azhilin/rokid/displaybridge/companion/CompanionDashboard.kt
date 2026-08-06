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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors

internal class CompanionDashboard(
    private val activity: Activity,
    private val layoutStore: DisplayLayoutStore,
    private val actions: Actions,
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
    )

    val root: ScrollView

    private val connectionChip = Chip(activity)
    private val connectionTitle = TextView(activity)
    private val connectionDetail = TextView(activity)
    private lateinit var connectionButton: MaterialButton
    private val externalButton: MaterialButton
    private val mirrorButton: MaterialButton
    private val errorCard: MaterialCardView
    private val errorText = TextView(activity)
    private val technicalDetails = TextView(activity)
    private val technicalToggle: MaterialButton
    private val metricValues = linkedMapOf<String, TextView>()
    private val primary = color(androidx.appcompat.R.attr.colorPrimary, 0xFF536BAF.toInt())
    private val primaryContainer = color(com.google.android.material.R.attr.colorPrimaryContainer, 0xFFDCE1FF.toInt())
    private val onPrimaryContainer = color(com.google.android.material.R.attr.colorOnPrimaryContainer, 0xFF0A1B4B.toInt())
    private val onPrimary = color(com.google.android.material.R.attr.colorOnPrimary, Color.WHITE)
    private val surface = color(com.google.android.material.R.attr.colorSurface, 0xFFFBF8FF.toInt())
    private val surfaceContainer = color(com.google.android.material.R.attr.colorSurfaceContainer, 0xFFF0EDF5.toInt())
    private val onSurface = color(com.google.android.material.R.attr.colorOnSurface, 0xFF1B1B20.toInt())
    private val onSurfaceVariant = color(com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF45464F.toInt())
    private val error = color(android.R.attr.colorError, 0xFFBA1A1A.toInt())
    private val onError = color(com.google.android.material.R.attr.colorOnError, Color.WHITE)
    private val errorContainer = color(com.google.android.material.R.attr.colorErrorContainer, 0xFFFFDAD6.toInt())
    private val onErrorContainer = color(com.google.android.material.R.attr.colorOnErrorContainer, 0xFF410002.toInt())

    init {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(40))
            setBackgroundColor(surface)
        }

        content.addView(header())
        content.addView(connectionCard(), margin(top = 22))

        content.addView(sectionTitle("Output mode"), margin(top = 26, bottom = 10))
        externalButton = modeButton(
            "External display",
            "For apps that support Android presentation displays",
            R.drawable.ic_external_display,
            actions.externalDisplay,
        )
        mirrorButton = modeButton(
            "Screen mirror",
            "Capture the phone after fresh Android consent",
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

        content.addView(sectionTitle("Frame placement"), margin(top = 26, bottom = 4))
        content.addView(body("Adjust position, size, preview and saved favorites on a dedicated screen."), margin(bottom = 10))
        val frameCard = card(surfaceContainer).apply {
            id = R.id.frame_card
            addView(MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = R.id.frame_placement_action
                text = "Open frame placement"
                isAllCaps = false
                minHeight = dp(56)
                setOnClickListener { actions.framePlacement() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(12), dp(12), dp(12), dp(12))
            })
        }
        content.addView(frameCard)

        content.addView(sectionTitle("Glasses gestures"), margin(top = 26, bottom = 4))
        content.addView(body("Configure swipes, tap, double tap and which frame favorites take part in gesture cycling."), margin(bottom = 10))
        content.addView(card(surfaceContainer).apply {
            id = R.id.gesture_settings_card
            addView(MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = R.id.gesture_settings_action
                text = "Open gesture settings"
                isAllCaps = false
                minHeight = dp(56)
                setOnClickListener { actions.gestureSettings() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(12), dp(12), dp(12), dp(12))
            })
        })

        content.addView(sectionTitle("Technical details"), margin(top = 26, bottom = 10))
        technicalDetails.apply {
            id = R.id.technical_details
            setTextColor(onSurfaceVariant)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            visibility = View.GONE
            setPadding(dp(4), dp(12), dp(4), dp(4))
        }
        technicalToggle = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Show diagnostics"
            isAllCaps = false
            setIconResource(R.drawable.ic_diagnostics)
            setOnClickListener {
                val show = technicalDetails.visibility != View.VISIBLE
                technicalDetails.visibility = if (show) View.VISIBLE else View.GONE
                text = if (show) "Hide diagnostics" else "Show diagnostics"
            }
        }
        content.addView(card(surfaceContainer).apply {
            id = R.id.technical_card
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                addView(sectionTitle("Live performance"), margin(bottom = 6))
                addView(metricsCard(), margin(bottom = 12))
                addView(technicalToggle)
                addView(technicalDetails)
            })
        })

        root = ScrollView(activity).apply {
            isFillViewport = true
            setBackgroundColor(surface)
            addView(content)
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
    }

    fun render(value: BridgeSnapshot) {
        connectionChip.text = value.connection.displayName()
        connectionTitle.text = when (value.connection) {
            ConnectionState.READY -> "Glasses connected"
            ConnectionState.ERROR -> "Connection needs attention"
            ConnectionState.DISCONNECTED -> "Waiting for glasses"
            else -> "Connecting to glasses"
        }
        connectionDetail.text = value.connectionDetail
        val healthy = value.connection == ConnectionState.READY
        connectionChip.chipBackgroundColor = ColorStateList.valueOf(if (healthy) primaryContainer else surfaceContainer)
        connectionChip.setTextColor(if (healthy) onPrimaryContainer else onSurfaceVariant)

        styleModeButton(externalButton, value.mode == BridgeMode.EXTERNAL_DISPLAY)
        styleModeButton(mirrorButton, value.mode == BridgeMode.SCREEN_MIRROR)
        val connected = value.connection == ConnectionState.READY || value.connection == ConnectionState.HANDSHAKING ||
            (value.connection == ConnectionState.ERROR && value.handshake.startsWith("DBP1 ready"))
        connectionButton.text = if (connected) "Disconnect" else "Connect"
        connectionButton.backgroundTintList = ColorStateList.valueOf(if (connected) error else primary)
        connectionButton.setTextColor(if (connected) onError else onPrimary)
        connectionButton.setOnClickListener { if (connected) actions.disconnect() else actions.connect() }

        metricValues.getValue("FPS").text = value.frameRate.toString()
        metricValues.getValue("BITRATE").text = if (value.bitrate > 0) "${value.bitrate / 1_000}k" else "—"
        metricValues.getValue("SENT").text = value.sentFrames.toString()
        metricValues.getValue("DECODED").text = value.decodedFrames.toString()
        metricValues.getValue("DROPPED").text = value.droppedFrames.toString()
        metricValues.getValue("QUEUE").text = value.queueDepth.toString()

        errorCard.visibility = if (value.error == null) View.GONE else View.VISIBLE
        errorText.text = value.error.orEmpty()
        technicalDetails.text = value.technicalText()
    }

    private fun header() = TextView(activity).apply {
        id = R.id.header_title
        text = "Display Bridge"
        textSize = 28f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(onSurface)
    }

    private fun connectionCard() = card(primaryContainer).apply {
        id = R.id.connection_card
        strokeColor = primary
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
                setTextColor(onPrimaryContainer)
                setPadding(0, dp(10), 0, 0)
            })
            addView(connectionDetail.apply {
                textSize = 14f
                setTextColor(onPrimaryContainer)
                alpha = .82f
                setPadding(0, dp(4), 0, dp(10))
            })
            connectionButton = MaterialButton(activity).apply {
                id = R.id.connection_action
                text = "Connect"
                isAllCaps = false
                minHeight = dp(48)
                backgroundTintList = ColorStateList.valueOf(primary)
                setTextColor(onPrimary)
                setOnClickListener { actions.connect() }
            }
            addView(connectionButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
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
        button.backgroundTintList = ColorStateList.valueOf(if (selected) primaryContainer else surfaceContainer)
        button.strokeColor = ColorStateList.valueOf(if (selected) primary else Color.TRANSPARENT)
        button.strokeWidth = if (selected) dp(2) else 0
        button.setTextColor(if (selected) onPrimaryContainer else onSurface)
        button.iconTint = ColorStateList.valueOf(if (selected) primary else onSurfaceVariant)
    }

    private fun metricsCard() = card(surfaceContainer).apply {
        id = R.id.metrics_card
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(12), dp(10), dp(12))
        }
        listOf(listOf("FPS", "BITRATE", "QUEUE"), listOf("SENT", "DECODED", "DROPPED")).forEach { rowKeys ->
            column.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                rowKeys.forEach { key -> addView(metric(key), LinearLayout.LayoutParams(0, dp(76), 1f)) }
            })
        }
        addView(column)
    }

    private fun metric(label: String) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(TextView(activity).apply {
            text = "—"
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(onSurface)
            metricValues[label] = this
        })
        addView(TextView(activity).apply {
            text = label
            textSize = 11f
            letterSpacing = .08f
            setTextColor(onSurfaceVariant)
        })
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
}

internal fun ConnectionState.displayName() = name.replace('_', ' ')
internal fun BridgeMode.displayName() = when (this) {
    BridgeMode.IDLE -> "IDLE"
    BridgeMode.EXTERNAL_DISPLAY -> "EXTERNAL DISPLAY"
    BridgeMode.SCREEN_MIRROR -> "SCREEN MIRROR"
}

internal fun BridgeSnapshot.technicalText() = buildString {
    appendLine("Connection : $connection — $connectionDetail")
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
