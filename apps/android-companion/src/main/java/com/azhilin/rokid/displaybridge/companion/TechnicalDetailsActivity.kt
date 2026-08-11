package com.azhilin.rokid.displaybridge.companion

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.azhilin.rokid.displaybridge.companion.service.BridgeService
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors

class TechnicalDetailsActivity : AppCompatActivity() {
    private lateinit var details: TextView
    private val runtimeListener: (BridgeSnapshot) -> Unit = { snapshot ->
        runOnUiThread { details.text = snapshot.technicalText() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        val onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, 0xFF1B1B20.toInt())
        details = TextView(this).apply {
            text = BridgeRuntime.snapshot().technicalText()
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(onSurface)
            setTextIsSelectable(true)
            setPadding(dp(20), dp(16), dp(20), dp(32))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(details, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(companionPage(this, CompanionPage.TECHNICAL, "Technical details", scroll).root)
    }

    override fun onStart() {
        super.onStart()
        BridgeRuntime.addListener(runtimeListener)
        BridgeRuntime.setCompanionVisible(true)
        ContextCompat.startForegroundService(
            this,
            Intent(this, BridgeService::class.java).setAction(BridgeService.ACTION_COMPANION_VISIBILITY),
        )
    }

    override fun onStop() {
        BridgeRuntime.setCompanionVisible(false)
        BridgeRuntime.removeListener(runtimeListener)
        super.onStop()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
