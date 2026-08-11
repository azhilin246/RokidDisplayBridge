package com.azhilin.rokid.displaybridge.companion

import android.content.Context
import com.azhilin.rokid.displaybridge.protocol.DisplayLayoutPayload
import com.azhilin.rokid.displaybridge.protocol.DoubleTapGestureAction
import com.azhilin.rokid.displaybridge.protocol.GestureSettingsPayload
import com.azhilin.rokid.displaybridge.protocol.SwipeGestureAction
import com.azhilin.rokid.displaybridge.protocol.TapGestureAction

data class CompanionGestureSettings(
    val policy: GestureSettingsPayload = GestureSettingsPayload(),
    val disabledFavoriteIds: Set<Long> = emptySet(),
)

enum class FavoriteCycleDirection { PREVIOUS, NEXT }

class GestureSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun load(): CompanionGestureSettings = CompanionGestureSettings(
        policy = GestureSettingsPayload(
            swipeAction = enumValue(preferences.getString(KEY_SWIPE, null), SwipeGestureAction.SEEK),
            tapAction = enumValue(preferences.getString(KEY_TAP, null), TapGestureAction.PLAY_PAUSE),
            doubleTapAction = DoubleTapGestureAction.EXIT_APP,
            reverseSwipeDirections = preferences.getBoolean(KEY_REVERSE, false),
            confirmExitWithSecondDoubleTap = false,
        ),
        disabledFavoriteIds = preferences.getStringSet(KEY_DISABLED_FAVORITES, emptySet()).orEmpty()
            .mapNotNull(String::toLongOrNull).filterTo(mutableSetOf()) { it > 0L },
    )

    @Synchronized
    fun save(value: CompanionGestureSettings) {
        preferences.edit()
            .putString(KEY_SWIPE, value.policy.swipeAction.name)
            .putString(KEY_TAP, value.policy.tapAction.name)
            .putString(KEY_DOUBLE_TAP, DoubleTapGestureAction.EXIT_APP.name)
            .putBoolean(KEY_REVERSE, value.policy.reverseSwipeDirections)
            .putBoolean(KEY_CONFIRM_EXIT, false)
            .putStringSet(KEY_DISABLED_FAVORITES, value.disabledFavoriteIds.filter { it > 0L }.mapTo(mutableSetOf(), Long::toString))
            .commit()
    }

    private inline fun <reified T : Enum<T>> enumValue(name: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback

    private companion object {
        const val PREFERENCES_NAME = "display_bridge_gesture_settings_v1"
        const val KEY_SWIPE = "swipe_action"
        const val KEY_TAP = "tap_action"
        const val KEY_DOUBLE_TAP = "double_tap_action"
        const val KEY_REVERSE = "reverse_swipe_directions"
        const val KEY_CONFIRM_EXIT = "confirm_exit_with_second_double_tap"
        const val KEY_DISABLED_FAVORITES = "disabled_favorite_ids"
    }
}

fun enabledGestureFavorites(
    favorites: List<FramePlacementFavorite>,
    disabledFavoriteIds: Set<Long>,
): List<FramePlacementFavorite> = favorites.filterNot { it.id in disabledFavoriteIds }

fun cycleGestureFavorite(
    favorites: List<FramePlacementFavorite>,
    disabledFavoriteIds: Set<Long>,
    currentLayout: DisplayLayoutPayload,
    direction: FavoriteCycleDirection,
): FramePlacementFavorite? {
    val enabled = enabledGestureFavorites(favorites, disabledFavoriteIds)
    if (enabled.isEmpty()) return null
    val currentIndex = enabled.indexOfFirst { it.layout.clamped() == currentLayout.clamped() }
    return when {
        currentIndex < 0 && direction == FavoriteCycleDirection.NEXT -> enabled.first()
        currentIndex < 0 -> enabled.last()
        direction == FavoriteCycleDirection.NEXT -> enabled[(currentIndex + 1) % enabled.size]
        else -> enabled[(currentIndex - 1 + enabled.size) % enabled.size]
    }
}
