package com.azhilin.rokid.displaybridge.companion

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.azhilin.rokid.displaybridge.protocol.DisplayLayoutPayload
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
class FramePlacementWorkflowTest {
    @Test fun `editor bounds horizontal movement and leaves ten percent visible vertically`() {
        val full = DisplayLayoutPayload()
        val left = DisplayLayoutEditorModel.geometry(full.copy(offsetXPermille = (-1_000).toShort()))
        val right = DisplayLayoutEditorModel.geometry(full.copy(offsetXPermille = 1_000))
        val top = DisplayLayoutEditorModel.geometry(full.copy(offsetYPermille = (-1_000).toShort()))
        val bottom = DisplayLayoutEditorModel.geometry(full.copy(offsetYPermille = 1_000))

        assertEquals(0.0, left.left, 0.0001)
        assertEquals(0.0, right.left, 0.0001)
        val reducedLeft = DisplayLayoutEditorModel.geometry(DisplayLayoutPayload(500u, -1_000, 0))
        val reducedRight = DisplayLayoutEditorModel.geometry(DisplayLayoutPayload(500u, 1_000, 0))
        assertEquals(0.0, reducedLeft.left, 0.0001)
        assertEquals(DisplayLayoutEditorModel.VIEWPORT_WIDTH - reducedRight.width, reducedRight.left, 0.0001)
        assertEquals(fullVisiblePixels(top.height), top.top + top.height, 0.0001)
        assertEquals(DisplayLayoutEditorModel.VIEWPORT_HEIGHT - fullVisiblePixels(bottom.height), bottom.top, 0.0001)
    }

    @Test fun `editor insets viewport and detects clipped region for hatching`() {
        val activity = themedActivity()
        val editor = DisplayLayoutEditorView(activity)
        editor.measure(exact(600), exact(720))
        editor.layout(0, 0, 600, 720)
        val viewport = editor.viewportBounds()

        assertTrue(viewport.left >= activity.resources.displayMetrics.density * DisplayLayoutEditorView.GESTURE_GUTTER_DP)
        assertTrue(viewport.top >= activity.resources.displayMetrics.density * DisplayLayoutEditorView.GESTURE_GUTTER_DP)
        assertFalse(editor.hasClippedRegion())
        editor.setLayoutValue(DisplayLayoutPayload(500u, 1_000, 0))
        val rightAligned = editor.frameBounds()
        assertTrue(rightAligned.left >= viewport.left)
        assertEquals(viewport.right, rightAligned.right, 1.1f)
        assertFalse(editor.hasClippedRegion())
        editor.setLayoutValue(DisplayLayoutPayload(offsetYPermille = 1_000))
        assertTrue(editor.hasClippedRegion())
    }

    @Test fun `favorites persist metadata and reorder deterministically`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = FramePlacementFavoritesStore(context)
        store.load().forEach { store.delete(it.id) }
        val first = store.add("Cinema | high", DisplayLayoutPayload(800u, -300, 200))
        val second = store.add("Mirror\nlow", DisplayLayoutPayload(600u, 250, -500))

        assertEquals(-300, first.layout.offsetXPermille.toInt())
        assertEquals(250, second.layout.offsetXPermille.toInt())

        assertEquals(listOf(second.id, first.id), FramePlacementFavoritesStore(context).load().map { it.id })
        store.move(first.id, 0)
        assertEquals(listOf(first.id, second.id), FramePlacementFavoritesStore(context).load().map { it.id })
        assertEquals(second.layout, store.layout(second.id))
        val updated = requireNotNull(store.update(second.id, "Edited", DisplayLayoutPayload(450u, 10, 20)))
        assertEquals("Edited", updated.name)
        assertEquals(450, store.layout(second.id)?.scalePermille?.toInt())
        assertTrue(store.delete(first.id))
        assertEquals(listOf(second.id), store.load().map { it.id })
        assertThrows(IllegalArgumentException::class.java) { store.add("   ", DisplayLayoutPayload()) }
    }

    @Test fun `favorite adapter supports direct drag order changes`() {
        val first = FramePlacementFavorite(11, "First", DisplayLayoutPayload())
        val second = FramePlacementFavorite(12, "Second", DisplayLayoutPayload(offsetYPermille = 200))
        val third = FramePlacementFavorite(13, "Third", DisplayLayoutPayload(offsetYPermille = 400))
        val adapter = FrameFavoriteAdapter({}, {}, {}, {})
        adapter.submitList(listOf(first, second, third))

        assertEquals(second, adapter.move(1, 0))
        assertEquals(listOf(12L, 11L, 13L), (0 until adapter.itemCount).map(adapter::getItemId))
    }

    @Test fun `favorite adapter highlights every exact layout match`() {
        val activity = themedActivity()
        val parent = RecyclerView(activity)
        val current = DisplayLayoutPayload(700u, 120, -340)
        val firstMatch = FramePlacementFavorite(1, "First match", current)
        val secondMatch = FramePlacementFavorite(2, "Second match", current.copy())
        val differentScale = FramePlacementFavorite(3, "Different scale", current.copy(scalePermille = 701u))
        val adapter = FrameFavoriteAdapter({}, {}, {}, {})
        adapter.submitList(listOf(firstMatch, secondMatch, differentScale))

        adapter.setCurrentLayout(current)
        val cards = (0 until adapter.itemCount).map { position ->
            adapter.onCreateViewHolder(parent, 0).also { adapter.onBindViewHolder(it, position) }.itemView as MaterialCardView
        }

        assertTrue(cards[0].isSelected)
        assertTrue(cards[0].isChecked)
        assertTrue(cards[1].isSelected)
        assertTrue(cards[1].isChecked)
        assertFalse(cards[2].isSelected)
        assertFalse(cards[2].isChecked)
        assertTrue(cards[0].strokeWidth > cards[2].strokeWidth)
        assertNotEquals(cards[0].cardBackgroundColor.defaultColor, cards[2].cardBackgroundColor.defaultColor)
        val selectedName = allViews(cards[0]).filterIsInstance<TextView>().first { it.text == "First match" }
        assertNotEquals(cards[0].cardBackgroundColor.defaultColor, selectedName.currentTextColor)
    }

    @Test fun `favorite action icons are centered in square buttons`() {
        val activity = themedActivity()
        val parent = RecyclerView(activity)
        val adapter = FrameFavoriteAdapter({}, {}, {}, {})
        adapter.submitList(listOf(FramePlacementFavorite(1, "Favorite", DisplayLayoutPayload())))
        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(holder, 0)

        val buttons = allViews(holder.itemView)
            .filterIsInstance<MaterialButton>()
            .filter { it.contentDescription?.toString() in setOf("Apply favorite", "Edit favorite", "Delete favorite") }
        assertEquals(3, buttons.size)
        buttons.forEach { button ->
            assertEquals(button.layoutParams.width, button.layoutParams.height)
            assertEquals(Gravity.CENTER, button.gravity)
            assertEquals(0, button.iconPadding)
            assertEquals(0, button.paddingLeft)
            assertEquals(0, button.paddingTop)
            assertEquals(0, button.paddingRight)
            assertEquals(0, button.paddingBottom)
        }
    }

    @Test fun `favorite list expands into the page without nested scrolling`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = FramePlacementFavoritesStore(context)
        store.load().forEach { store.delete(it.id) }
        repeat(6) { store.add("Favorite $it", DisplayLayoutPayload(offsetYPermille = (it * 50).toShort())) }
        val controller = Robolectric.buildActivity(FramePlacementActivity::class.java).setup()
        val activity = controller.get()
        val recycler = activity.findViewById<RecyclerView>(R.id.favorites_container)

        assertFalse(recycler.isNestedScrollingEnabled)
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, recycler.layoutParams.height)
        recycler.measure(exact(1080), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        recycler.layout(0, 0, recycler.measuredWidth, recycler.measuredHeight)
        assertEquals(6, recycler.childCount)
        assertTrue(recycler.measuredHeight > (320 * activity.resources.displayMetrics.density).toInt())

        controller.pause().stop().destroy()
        store.load().forEach { store.delete(it.id) }
    }

    @Test fun `preview content follows the placed frame and is clipped by the viewport`() {
        val activity = themedActivity()
        val stage = FramePlacementStage(activity)
        stage.measure(exact(600), exact(720))
        stage.layout(0, 0, 600, 720)
        stage.editor.setLayoutValue(DisplayLayoutPayload(500u, 1_000, -1_000))
        stage.syncPreviewPlacement()

        val expected = stage.editor.frameBounds()
        val clip = stage.previewView.parent as View
        assertEquals(expected.left, (clip.left + stage.previewView.left).toFloat(), 1.1f)
        assertEquals(expected.top, (clip.top + stage.previewView.top).toFloat(), 1.1f)
        assertEquals(expected.right, (clip.left + stage.previewView.right).toFloat(), 1.1f)
        assertEquals(expected.bottom, (clip.top + stage.previewView.bottom).toFloat(), 1.1f)
        assertTrue(stage.editor.hasClippedRegion())
        assertEquals(stage.editor.viewportBounds().width(), clip.width.toFloat(), 1.1f)
        assertEquals(stage.editor.viewportBounds().height(), clip.height.toFloat(), 1.1f)
    }

    @Test fun `dedicated page exposes inset stage favorites reset and cold off preview`() {
        BridgePreviewFrames.setEnabled(false)
        val controller = Robolectric.buildActivity(FramePlacementActivity::class.java).setup()
        val activity = controller.get()

        assertNotNull(activity.findViewById<View>(R.id.frame_stage))
        assertNotNull(activity.findViewById<View>(R.id.frame_editor))
        assertNotNull(activity.findViewById<View>(R.id.frame_preview_surface))
        assertNotNull(activity.findViewById<View>(R.id.reset_frame_action))
        assertNotNull(activity.findViewById<View>(R.id.favorites_container))
        assertTrue(activity.findViewById<View>(R.id.favorites_container) is RecyclerView)
        assertNotNull(activity.findViewById<View>(R.id.add_favorite_action))
        assertTrue(activity.findViewById<com.google.android.material.navigation.NavigationView>(R.id.navigation_view)
            .menu.findItem(R.id.nav_layout).isChecked)
        val toggle = activity.findViewById<MaterialSwitch>(R.id.preview_content_toggle)
        val preview = activity.findViewById<View>(R.id.frame_preview_surface)
        assertFalse(toggle.isChecked)
        assertEquals(View.INVISIBLE, preview.visibility)
        assertFalse(BridgePreviewFrames.isEnabled)
        toggle.isChecked = true
        assertTrue(BridgePreviewFrames.isEnabled)
        assertEquals(View.VISIBLE, preview.visibility)

        activity.findViewById<View>(R.id.add_favorite_action).performClick()
        val dialog = ShadowDialog.getLatestDialog()
        assertTrue(dialog.isShowing)
        val dialogText = allText(dialog.window!!.decorView)
        assertTrue(dialogText.any { it == "Add favorite" })
        val hints = inputHints(dialog.window!!.decorView)
        assertTrue(hints.any { it.contains("X (−1000…1000)") })
        assertTrue(hints.any { it.contains("Y (−1000…1000)") })
        dialog.dismiss()
        controller.stop()
        assertFalse(BridgePreviewFrames.isEnabled)
        assertEquals(View.INVISIBLE, preview.visibility)
    }

    private fun themedActivity(): Activity {
        val controller = Robolectric.buildActivity(Activity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        return controller.setup().get()
    }

    private fun exact(value: Int) = View.MeasureSpec.makeMeasureSpec(value, View.MeasureSpec.EXACTLY)
    private fun fullVisiblePixels(size: Double) = size * 0.1

    private fun allText(view: View): List<String> = buildList {
        if (view is TextView) add(view.text.toString())
        if (view is ViewGroup) repeat(view.childCount) { addAll(allText(view.getChildAt(it))) }
    }

    private fun inputHints(view: View): List<String> = buildList {
        if (view is TextInputLayout) add(view.hint?.toString().orEmpty())
        if (view is ViewGroup) repeat(view.childCount) { addAll(inputHints(view.getChildAt(it))) }
    }

    private fun allViews(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) repeat(view.childCount) { addAll(allViews(view.getChildAt(it))) }
    }

}
