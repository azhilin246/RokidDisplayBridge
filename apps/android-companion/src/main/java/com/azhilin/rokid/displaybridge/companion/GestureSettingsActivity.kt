package com.azhilin.rokid.displaybridge.companion

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.azhilin.rokid.displaybridge.companion.service.BridgeService
import com.azhilin.rokid.displaybridge.protocol.DoubleTapGestureAction
import com.azhilin.rokid.displaybridge.protocol.GestureSettingsPayload
import com.azhilin.rokid.displaybridge.protocol.SwipeGestureAction
import com.azhilin.rokid.displaybridge.protocol.TapGestureAction
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.DynamicColors
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.switchmaterial.SwitchMaterial

class GestureSettingsActivity : AppCompatActivity() {
    private lateinit var store: GestureSettingsStore
    private lateinit var favoritesStore: FramePlacementFavoritesStore
    private lateinit var swipeGroup: RadioGroup
    private lateinit var tapGroup: RadioGroup
    private lateinit var reverseSwitch: SwitchMaterial
    private val favoriteChecks = linkedMapOf<Long, MaterialCheckBox>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        store = GestureSettingsStore(this)
        favoritesStore = FramePlacementFavoritesStore(this)
        val saved = store.load()
        setContentView(buildContent(saved))
    }

    private fun buildContent(saved: CompanionGestureSettings): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(40))
            addView(title("Glasses gestures"))
            addView(body("Set actions for swipes and taps."), margin(top = 6, bottom = 18))
        }

        tapGroup = enumCard(
            "Tap",
            listOf(
                TapGestureAction.NONE to "Do nothing",
                TapGestureAction.PLAY_PAUSE to "Play / Pause",
                TapGestureAction.SHOW_OVERLAY to "Show control overlay",
            ),
            saved.policy.tapAction,
        )
        content.addView(tapGroup, margin(bottom = 18))

        content.addView(section("Double tap"))
        content.addView(body("Double tap exits Display Bridge."),
            margin(top = 4, bottom = 18))

        content.addView(section("Swipes"), margin(bottom = 4))
        swipeGroup = enumCard(
            "Swipe action",
            listOf(
                SwipeGestureAction.NONE to "Do nothing",
                SwipeGestureAction.SEEK to "Seek backward / forward one step",
                SwipeGestureAction.PLAYLIST to "Previous / next playlist item",
                SwipeGestureAction.FAVORITES to "Previous / next enabled frame favorite",
            ),
            saved.policy.swipeAction,
            emphasizedLabel = false,
        )
        content.addView(swipeGroup)
        reverseSwitch = SwitchMaterial(this).apply {
            text = "Reverse swipe directions"
            isChecked = saved.policy.reverseSwipeDirections
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        content.addView(reverseSwitch, margin(top = 4, bottom = 10))
        content.addView(body("Favorites used by swipe cycling"), margin(bottom = 4))
        val favorites = favoritesStore.load()
        if (favorites.isEmpty()) {
            content.addView(body("Create favorites in Layout."), margin(top = 6))
        } else {
            favorites.forEach { favorite ->
                content.addView(MaterialCheckBox(this).apply {
                    text = favorite.name
                    isChecked = favorite.id !in saved.disabledFavoriteIds
                    favoriteChecks[favorite.id] = this
                })
            }
            content.addView(body("Order follows Layout."))
        }
        content.addView(
            android.view.View(this),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18)),
        )

        content.addView(MaterialButton(this).apply {
            id = R.id.save_gesture_settings_action
            text = "Save"
            isAllCaps = false
            minHeight = dp(56)
            setOnClickListener { save() }
        })

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
        return companionPage(this, CompanionPage.GESTURES, "Gestures", scroll).root
    }

    private fun save() {
        val policy = GestureSettingsPayload(
            swipeAction = selected(swipeGroup, SwipeGestureAction.entries),
            tapAction = selected(tapGroup, TapGestureAction.entries),
            doubleTapAction = DoubleTapGestureAction.EXIT_APP,
            reverseSwipeDirections = reverseSwitch.isChecked,
            confirmExitWithSecondDoubleTap = false,
        )
        val disabled = favoriteChecks.filterValues { !it.isChecked }.keys
        store.save(CompanionGestureSettings(policy, disabled))
        ContextCompat.startForegroundService(this, Intent(this, BridgeService::class.java).setAction(BridgeService.ACTION_APPLY_GESTURE_SETTINGS))
        Toast.makeText(this, "Gesture settings saved", Toast.LENGTH_SHORT).show()
    }

    private fun <T : Enum<T>> enumCard(
        label: String,
        values: List<Pair<T, String>>,
        selected: T,
        emphasizedLabel: Boolean = true,
    ): RadioGroup =
        RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            addView(
                if (emphasizedLabel) section(label) else body(label),
                margin(left = 4, top = 4, bottom = 4),
            )
            values.forEach { (value, textValue) ->
                addView(MaterialRadioButton(this@GestureSettingsActivity).apply {
                    id = ViewCompat.generateViewId()
                    text = textValue
                    isChecked = value == selected
                    tag = value.name
                })
            }
        }

    private fun <T : Enum<T>> selected(group: RadioGroup, values: List<T>): T {
        val button = group.findViewById<MaterialRadioButton>(group.checkedRadioButtonId)
        return values.firstOrNull { it.name == button?.tag } ?: values.first()
    }

    private fun title(value: String) = TextView(this).apply { text = value; textSize = 28f; typeface = Typeface.DEFAULT_BOLD }
    private fun section(value: String) = TextView(this).apply { text = value; textSize = 18f; typeface = Typeface.DEFAULT_BOLD }
    private fun body(value: String) = TextView(this).apply { text = value; textSize = 14f; alpha = .75f }
    private fun margin(left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(left), dp(top), dp(right), dp(bottom))
        }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
