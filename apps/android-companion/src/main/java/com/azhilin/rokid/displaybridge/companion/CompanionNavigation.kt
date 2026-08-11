package com.azhilin.rokid.displaybridge.companion

import android.app.Activity
import android.content.res.ColorStateList
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.navigation.NavigationView

internal enum class CompanionPage(val menuId: Int, val label: String) {
    HOME(R.id.nav_home, "Home"),
    LAYOUT(R.id.nav_layout, "Layout"),
    GESTURES(R.id.nav_gestures, "Gestures"),
    TECHNICAL(R.id.nav_technical, "Technical details"),
}

internal data class CompanionPageShell(
    val root: DrawerLayout,
    val toolbar: MaterialToolbar,
    val navigation: NavigationView,
)

internal fun companionPage(
    activity: Activity,
    currentPage: CompanionPage,
    title: String,
    content: View,
    toolbarId: Int = View.NO_ID,
): CompanionPageShell {
    WindowCompat.setDecorFitsSystemWindows(activity.window, false)
    val surface = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorSurface, 0xFFFBF8FF.toInt())
    val onSurface = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOnSurface, 0xFF1B1B20.toInt())
    val onSurfaceVariant = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF45464F.toInt())
    val primary = MaterialColors.getColor(activity, androidx.appcompat.R.attr.colorPrimary, 0xFF536BAF.toInt())
    val onPrimary = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOnPrimary, 0xFFFFFFFF.toInt())
    val density = activity.resources.displayMetrics.density
    fun dp(value: Int) = (value * density).toInt()

    val root = DrawerLayout(activity).apply {
        id = R.id.navigation_drawer
        setBackgroundColor(surface)
    }
    val toolbar = MaterialToolbar(activity).apply {
        id = toolbarId
        this.title = title
        setNavigationIcon(R.drawable.ic_menu)
        navigationContentDescription = "Open navigation"
    }
    val page = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(surface)
        addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }
    root.addView(page, DrawerLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

    lateinit var drawerHeader: LinearLayout
    val navigation = NavigationView(activity).apply {
        id = R.id.navigation_view
        setBackgroundColor(surface)
        fitsSystemWindows = false
        itemBackground = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(primary)
            })
            addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
        }
        itemTextColor = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(onPrimary, onSurface),
        )
        drawerHeader = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(20))
            addView(TextView(activity).apply {
                text = "Display Bridge"
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(onSurface)
            })
            addView(TextView(activity).apply {
                text = "Rokid connection and display"
                textSize = 14f
                setTextColor(onSurfaceVariant)
                setPadding(0, dp(4), 0, 0)
            })
        }
        addHeaderView(drawerHeader)
        CompanionPage.entries.forEachIndexed { index, destination ->
            menu.add(0, destination.menuId, index, destination.label)
                .setCheckable(true)
        }
        setCheckedItem(currentPage.menuId)
    }
    root.addView(navigation, DrawerLayout.LayoutParams(dp(320), ViewGroup.LayoutParams.MATCH_PARENT).apply {
        gravity = GravityCompat.START
    })

    toolbar.setNavigationOnClickListener { root.openDrawer(GravityCompat.START) }
    navigation.setNavigationItemSelectedListener { item ->
        val destination = CompanionPage.entries.firstOrNull { it.menuId == item.itemId }
            ?: return@setNavigationItemSelectedListener false
        navigation.setCheckedItem(currentPage.menuId)
        root.closeDrawer(GravityCompat.START, false)
        if (destination == currentPage) {
            true
        } else {
            navigateTo(activity, destination)
            false
        }
    }

    val toolbarHorizontalPadding = toolbar.paddingLeft to toolbar.paddingRight
    val contentHorizontalPadding = content.paddingLeft to content.paddingRight
    val contentBottomPadding = content.paddingBottom
    val headerPadding = intArrayOf(
        drawerHeader.paddingLeft,
        drawerHeader.paddingTop,
        drawerHeader.paddingRight,
        drawerHeader.paddingBottom,
    )
    val navigationBottomPadding = navigation.paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        toolbar.setPadding(toolbarHorizontalPadding.first, bars.top, toolbarHorizontalPadding.second, toolbar.paddingBottom)
        content.setPadding(
            contentHorizontalPadding.first + bars.left,
            content.paddingTop,
            contentHorizontalPadding.second + bars.right,
            contentBottomPadding + bars.bottom,
        )
        drawerHeader.setPadding(
            headerPadding[0] + bars.left,
            headerPadding[1] + bars.top,
            headerPadding[2] + bars.right,
            headerPadding[3],
        )
        navigation.setPadding(
            navigation.paddingLeft,
            navigation.paddingTop,
            navigation.paddingRight,
            navigationBottomPadding + bars.bottom,
        )
        insets
    }

    if (activity is ComponentActivity) {
        activity.onBackPressedDispatcher.addCallback(activity, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (root.isDrawerOpen(GravityCompat.START)) {
                    root.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    activity.onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }
    return CompanionPageShell(root, toolbar, navigation)
}

private fun navigateTo(activity: Activity, page: CompanionPage) {
    if (page == CompanionPage.HOME && activity !is MainActivity) {
        suppressCloseTransition(activity)
        activity.finish()
        return
    }
    val target = when (page) {
        CompanionPage.HOME -> MainActivity::class.java
        CompanionPage.LAYOUT -> FramePlacementActivity::class.java
        CompanionPage.GESTURES -> GestureSettingsActivity::class.java
        CompanionPage.TECHNICAL -> TechnicalDetailsActivity::class.java
    }
    activity.startActivity(Intent(activity, target).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
    if (activity !is MainActivity) activity.finish()
}

@Suppress("DEPRECATION")
private fun suppressCloseTransition(activity: Activity) {
    if (android.os.Build.VERSION.SDK_INT >= 34) {
        activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
    } else {
        activity.overridePendingTransition(0, 0)
    }
}
