package com.azhilin.rokid.displaybridge.companion

import android.content.Context
import com.azhilin.rokid.displaybridge.protocol.DisplayLayoutPayload
import java.nio.charset.StandardCharsets
import java.util.Base64

data class FramePlacementFavorite(
    val id: Long,
    val name: String,
    val layout: DisplayLayoutPayload,
)

internal fun FramePlacementFavorite.matchesLayout(value: DisplayLayoutPayload): Boolean {
    val favoriteLayout = layout.clamped()
    val currentLayout = value.clamped()
    return favoriteLayout.scalePermille == currentLayout.scalePermille &&
        favoriteLayout.offsetXPermille == currentLayout.offsetXPermille &&
        favoriteLayout.offsetYPermille == currentLayout.offsetYPermille
}

class FramePlacementFavoritesStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun load(): List<FramePlacementFavorite> = decode(preferences.getString(KEY_FAVORITES, null).orEmpty())

    @Synchronized
    fun add(name: String, layout: DisplayLayoutPayload): FramePlacementFavorite {
        val effectiveName = name.trim().also { require(it.isNotEmpty()) { "Favorite name must not be blank" } }
        val favorites = load().toMutableList()
        val nextId = (favorites.maxOfOrNull(FramePlacementFavorite::id) ?: 0L) + 1L
        val favorite = FramePlacementFavorite(nextId, effectiveName, layout.clamped())
        favorites.add(0, favorite)
        persist(favorites)
        return favorite
    }

    @Synchronized
    fun update(id: Long, name: String, layout: DisplayLayoutPayload): FramePlacementFavorite? {
        val effectiveName = name.trim().also { require(it.isNotEmpty()) { "Favorite name must not be blank" } }
        val favorites = load().toMutableList()
        val index = favorites.indexOfFirst { it.id == id }
        if (index < 0) return null
        val updated = FramePlacementFavorite(id, effectiveName, layout.clamped())
        favorites[index] = updated
        persist(favorites)
        return updated
    }

    @Synchronized
    fun delete(id: Long): Boolean {
        val favorites = load().toMutableList()
        val removed = favorites.removeAll { it.id == id }
        if (removed) persist(favorites)
        return removed
    }

    @Synchronized
    fun move(id: Long, destinationIndex: Int): List<FramePlacementFavorite> {
        val favorites = load().toMutableList()
        val sourceIndex = favorites.indexOfFirst { it.id == id }
        if (sourceIndex < 0 || favorites.isEmpty()) return favorites
        val favorite = favorites.removeAt(sourceIndex)
        favorites.add(destinationIndex.coerceIn(0, favorites.size), favorite)
        persist(favorites)
        return favorites
    }

    fun layout(id: Long): DisplayLayoutPayload? = load().firstOrNull { it.id == id }?.layout

    private fun persist(favorites: List<FramePlacementFavorite>) {
        preferences.edit().putString(KEY_FAVORITES, encode(favorites)).commit()
    }

    private fun encode(favorites: List<FramePlacementFavorite>): String = favorites.joinToString("\n") { favorite ->
        listOf(
            favorite.id.toString(),
            ENCODER.encodeToString(favorite.name.toByteArray(StandardCharsets.UTF_8)),
            favorite.layout.scalePermille.toString(),
            favorite.layout.offsetXPermille.toString(),
            favorite.layout.offsetYPermille.toString(),
        ).joinToString("|")
    }

    private fun decode(encoded: String): List<FramePlacementFavorite> = encoded.lineSequence().mapNotNull { line ->
        val fields = line.split('|')
        if (fields.size != 5) return@mapNotNull null
        runCatching {
            FramePlacementFavorite(
                id = fields[0].toLong().also { require(it > 0) },
                name = String(DECODER.decode(fields[1]), StandardCharsets.UTF_8).trim().also { require(it.isNotEmpty()) },
                layout = DisplayLayoutPayload(
                    fields[2].toInt().coerceIn(0, UShort.MAX_VALUE.toInt()).toUShort(),
                    fields[3].toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort(),
                    fields[4].toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort(),
                ).clamped(),
            )
        }.getOrNull()
    }.toList()

    private companion object {
        const val PREFERENCES_NAME = "display_bridge_frame_favorites_v1"
        const val KEY_FAVORITES = "ordered_favorites"
        val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        val DECODER: Base64.Decoder = Base64.getUrlDecoder()
    }
}
