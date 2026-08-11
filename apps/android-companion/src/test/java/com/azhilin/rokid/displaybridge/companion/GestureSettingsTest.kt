package com.azhilin.rokid.displaybridge.companion

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.azhilin.rokid.displaybridge.protocol.DisplayLayoutPayload
import com.azhilin.rokid.displaybridge.protocol.DoubleTapGestureAction
import com.azhilin.rokid.displaybridge.protocol.GestureSettingsPayload
import com.azhilin.rokid.displaybridge.protocol.SwipeGestureAction
import com.azhilin.rokid.displaybridge.protocol.TapGestureAction
import com.google.android.material.card.MaterialCardView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric

@RunWith(RobolectricTestRunner::class)
class GestureSettingsTest {
    @Test fun `gesture settings activity exposes all controls and save action`() {
        val activity = Robolectric.buildActivity(GestureSettingsActivity::class.java).setup().get()
        val text = collectText(activity.findViewById(android.R.id.content))
        assertTrue(text.any { it == "Glasses gestures" })
        assertTrue(text.any { it.contains("Seek backward") })
        assertTrue(text.any { it.contains("playlist item") })
        assertTrue(text.any { it.contains("frame favorite") })
        assertTrue(text.any { it.contains("Reverse swipe") })
        assertTrue(text.any { it.contains("Double tap exits Display Bridge") })
        assertFalse(text.any { it.contains("wireless pairing", true) })
        assertFalse(text.any { it.contains("main screen", true) })
        assertTrue(activity.findViewById<android.view.View>(R.id.save_gesture_settings_action) != null)
        assertTrue(activity.findViewById<com.google.android.material.navigation.NavigationView>(R.id.navigation_view)
            .menu.findItem(R.id.nav_gestures).isChecked)
        assertTrue(text.indexOfFirst { it == "Tap" } < text.indexOfFirst { it == "Double tap" })
        assertTrue(text.indexOfFirst { it == "Double tap" } < text.indexOfFirst { it == "Swipes" })
        assertTrue(text.indexOfFirst { it == "Swipes" } < text.indexOfFirst { it == "Swipe action" })
        assertFalse(text.any { it == "Swipes & favorites" })
        assertFalse(containsMaterialCard(activity.findViewById(android.R.id.content)))
        val swipeLabel = findText(activity.findViewById(android.R.id.content), "Swipe action")
        val favoritesLabel = findText(activity.findViewById(android.R.id.content), "Favorites used by swipe cycling")
        assertEquals(favoritesLabel.textSize, swipeLabel.textSize, 0.01f)
        assertEquals(favoritesLabel.typeface, swipeLabel.typeface)
        assertEquals(favoritesLabel.alpha, swipeLabel.alpha, 0.01f)
    }

    @Test fun `settings persist policy and disabled favorites while new ids remain enabled`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("display_bridge_gesture_settings_v1", Context.MODE_PRIVATE).edit().clear().commit()
        val store = GestureSettingsStore(context)
        val policy = GestureSettingsPayload(
            SwipeGestureAction.PLAYLIST,
            TapGestureAction.SHOW_OVERLAY,
            DoubleTapGestureAction.EXIT_APP,
            reverseSwipeDirections = true,
            confirmExitWithSecondDoubleTap = false,
        )
        store.save(CompanionGestureSettings(policy, setOf(2L)))

        val restored = store.load()
        assertEquals(policy.copy(doubleTapAction = DoubleTapGestureAction.EXIT_APP, confirmExitWithSecondDoubleTap = false), restored.policy)
        assertEquals(setOf(2L), restored.disabledFavoriteIds)
        val favorites = listOf(favorite(1), favorite(2), favorite(3))
        assertEquals(listOf(1L, 3L), enabledGestureFavorites(favorites, restored.disabledFavoriteIds).map { it.id })
        assertTrue(3L !in restored.disabledFavoriteIds)
    }

    @Test fun `legacy main menu gesture migrates to direct application exit`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("display_bridge_gesture_settings_v1", Context.MODE_PRIVATE)
            .edit().clear().putString("double_tap_action", DoubleTapGestureAction.MAIN_MENU.name).commit()

        assertEquals(DoubleTapGestureAction.EXIT_APP, GestureSettingsStore(context).load().policy.doubleTapAction)
    }

    @Test fun `favorite cycling follows current order wraps and starts predictably`() {
        val first = favorite(1, y = -500)
        val second = favorite(2, y = 0)
        val third = favorite(3, y = 500)
        val ordered = listOf(third, first, second)

        assertEquals(third, cycleGestureFavorite(ordered, setOf(1), DisplayLayoutPayload(), FavoriteCycleDirection.NEXT))
        assertEquals(third, cycleGestureFavorite(ordered, setOf(1), second.layout, FavoriteCycleDirection.NEXT))
        assertEquals(second, cycleGestureFavorite(ordered, setOf(1), third.layout, FavoriteCycleDirection.PREVIOUS))
        assertFalse(enabledGestureFavorites(ordered, setOf(1)).any { it.id == 1L })
        assertEquals(null, cycleGestureFavorite(ordered, setOf(1, 2, 3), second.layout, FavoriteCycleDirection.NEXT))
    }

    private fun favorite(id: Long, y: Int = id.toInt() * 10) = FramePlacementFavorite(
        id,
        "Favorite $id",
        DisplayLayoutPayload(750u, 0, y.toShort()),
    )

    private fun collectText(view: android.view.View): List<String> = buildList {
        if (view is android.widget.TextView) add(view.text.toString())
        if (view is android.view.ViewGroup) repeat(view.childCount) { addAll(collectText(view.getChildAt(it))) }
    }

    private fun containsMaterialCard(view: android.view.View): Boolean =
        view is MaterialCardView ||
            (view is android.view.ViewGroup && (0 until view.childCount).any { containsMaterialCard(view.getChildAt(it)) })

    private fun findText(view: android.view.View, value: String): android.widget.TextView {
        if (view is android.widget.TextView && view.text.toString() == value) return view
        if (view is android.view.ViewGroup) {
            repeat(view.childCount) {
                runCatching { findText(view.getChildAt(it), value) }.getOrNull()?.let { return it }
            }
        }
        error("Text not found: $value")
    }
}
