package com.azhilin.rokid.displaybridge.companion

import android.os.Bundle
import android.content.Intent
import android.content.Context
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.Closeable
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.azhilin.rokid.displaybridge.protocol.DisplayLayoutPayload
import com.azhilin.rokid.displaybridge.companion.service.BridgeService
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class FramePlacementActivity : AppCompatActivity() {
    private lateinit var layoutStore: DisplayLayoutStore
    private lateinit var favoritesStore: FramePlacementFavoritesStore
    private lateinit var stage: FramePlacementStage
    private lateinit var favoritesRecycler: RecyclerView
    private lateinit var favoritesAdapter: FrameFavoriteAdapter
    private lateinit var favoritesEmpty: TextView
    private lateinit var favoriteTouchHelper: ItemTouchHelper
    private lateinit var previewToggle: MaterialSwitch
    private var previewDecoder: PreviewTextureDecoder? = null
    private var previewSubscription: Closeable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        layoutStore = DisplayLayoutStore(this)
        favoritesStore = FramePlacementFavoritesStore(this)
        setContentView(buildContent())
        stage.editor.setLayoutValue(layoutStore.load())
        stage.syncPreviewPlacement()
        stage.editor.onLayoutEdited = { value, _ ->
            stage.syncPreviewPlacement()
            applyLayout(value)
        }
        renderFavorites()
    }

    override fun onStop() {
        previewToggle.isChecked = false
        disablePreview()
        BridgeRuntime.setCompanionVisible(false)
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        BridgeRuntime.setCompanionVisible(true)
        ContextCompat.startForegroundService(
            this,
            Intent(this, BridgeService::class.java).setAction(BridgeService.ACTION_COMPANION_VISIBILITY),
        )
    }

    private fun buildContent(): View {
        val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, 0xFFFFFBFE.toInt())
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surface)
        }
        val toolbar = MaterialToolbar(this).apply {
            title = "Frame Placement"
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            navigationContentDescription = "Back"
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(28))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        applySystemInsets(root, toolbar, scroll)

        content.addView(body("Drag within the viewport to position. Horizontal edges stay inside the glasses; vertically, at least 10% remains visible. Drag a corner to resize."))
        stage = FramePlacementStage(this)
        content.addView(MaterialCardView(this).apply {
            radius = dp(24).toFloat()
            strokeWidth = dp(1)
            addView(stage, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(440)))
        }, margin(top = 14, bottom = 12))

        previewToggle = MaterialSwitch(this).apply {
            id = R.id.preview_content_toggle
            text = "Preview Content"
            isChecked = false
            setOnCheckedChangeListener { _, checked ->
                if (checked) enablePreview() else disablePreview()
            }
        }
        content.addView(previewToggle, margin(bottom = 8))
        content.addView(body("Low-rate, latest-frame-only preview. It never changes bridge bitrate or video transport."), margin(bottom = 12))
        content.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            id = R.id.reset_frame_action
            text = "Reset Frame"
            isAllCaps = false
            setOnClickListener {
                val reset = DisplayLayoutEditorModel.reset()
                stage.editor.setLayoutValue(reset)
                stage.syncPreviewPlacement()
                applyLayout(reset)
            }
        }, margin(bottom = 28))

        content.addView(heading("Favorites"), margin(bottom = 8))
        favoritesEmpty = body("No favorites saved yet.").apply { visibility = View.GONE }
        content.addView(favoritesEmpty, margin(bottom = 8))
        favoritesAdapter = FrameFavoriteAdapter(
            onApply = ::applyFavorite,
            onDelete = { favorite -> favoritesStore.delete(favorite.id); renderFavorites() },
            onDragStart = { holder -> favoriteTouchHelper.startDrag(holder) },
        )
        favoritesRecycler = FullyExpandedRecyclerView(this).apply {
            id = R.id.favorites_container
            layoutManager = LinearLayoutManager(this@FramePlacementActivity)
            adapter = favoritesAdapter
            isNestedScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        favoriteTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0,
        ) {
            override fun isLongPressDragEnabled(): Boolean = false

            override fun onMove(
                recyclerView: RecyclerView,
                source: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                val from = source.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                val moved = favoritesAdapter.move(from, to) ?: return false
                favoritesStore.move(moved.id, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
        }).also { it.attachToRecyclerView(favoritesRecycler) }
        content.addView(favoritesRecycler)
        content.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            id = R.id.add_favorite_action
            text = "Add"
            isAllCaps = false
            minHeight = dp(52)
            setOnClickListener { showAddFavoriteDialog() }
        }, margin(top = 2, bottom = 8))
        return root
    }

    private fun renderFavorites() {
        val favorites = favoritesStore.load()
        favoritesEmpty.visibility = if (favorites.isEmpty()) View.VISIBLE else View.GONE
        favoritesRecycler.visibility = if (favorites.isEmpty()) View.GONE else View.VISIBLE
        favoritesAdapter.submitList(favorites)
    }

    private fun applyFavorite(favorite: FramePlacementFavorite) {
        stage.editor.setLayoutValue(favorite.layout)
        stage.syncPreviewPlacement()
        applyLayout(favorite.layout)
    }

    private fun showAddFavoriteDialog() {
        val current = stage.editor.value.clamped()
        val nameLayout = inputField("Name", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        val xLayout = inputField(
            "X (−1000…1000)",
            current.offsetXPermille.toString(),
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED,
            "−1000 left · 0 center · 1000 right",
        )
        val yLayout = inputField(
            "Y (−1000…1000)",
            current.offsetYPermille.toString(),
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED,
            "−1000 up · 0 center · 1000 down",
        )
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(nameLayout, margin(bottom = 8))
            addView(xLayout, margin(bottom = 8))
            addView(yLayout)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Add favorite")
            .setView(form)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = editText(nameLayout).text?.toString().orEmpty().trim()
                val x = parseOffset(xLayout)
                val y = parseOffset(yLayout)
                nameLayout.error = if (name.isEmpty()) "Enter a name" else null
                if (name.isEmpty() || x == null || y == null) return@setOnClickListener
                val layout = current.copy(offsetXPermille = x.toShort(), offsetYPermille = y.toShort()).clamped()
                val favorite = favoritesStore.add(name, layout)
                applyFavorite(favorite)
                renderFavorites()
                dialog.dismiss()
            }
        }
        dialog.show()
        editText(nameLayout).requestFocus()
    }

    private fun inputField(hint: String, value: String, inputType: Int, helper: String? = null) =
        TextInputLayout(this).apply {
            this.hint = hint
            helperText = helper
            addView(TextInputEditText(context).apply {
                id = ViewCompat.generateViewId()
                setText(value)
                this.inputType = inputType
                setSingleLine()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

    private fun editText(layout: TextInputLayout): TextInputEditText = layout.editText as TextInputEditText

    private fun parseOffset(layout: TextInputLayout): Int? {
        val value = editText(layout).text?.toString()?.toIntOrNull()
        layout.error = if (value == null || value !in -1_000..1_000) "Enter a value from −1000 to 1000" else null
        return value?.takeIf { it in -1_000..1_000 }
    }

    private fun applyLayout(value: DisplayLayoutPayload) {
        val effective = value.clamped()
        layoutStore.save(effective)
        BridgeRuntime.submitDisplayLayout(effective)
    }

    private fun disablePreview() {
        BridgePreviewFrames.setEnabled(false)
        previewSubscription?.close()
        previewSubscription = null
        previewDecoder?.close()
        previewDecoder = null
        stage.setPreviewEnabled(false)
    }

    private fun enablePreview() {
        disablePreview()
        stage.setPreviewEnabled(true)
        val decoder = PreviewTextureDecoder(stage.previewView)
        previewDecoder = decoder
        previewSubscription = BridgePreviewFrames.subscribe(decoder.updateListener)
        BridgePreviewFrames.setEnabled(true)
    }

    private fun applySystemInsets(root: View, toolbar: View, scroll: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            toolbar.setPadding(toolbar.paddingLeft, bars.top, toolbar.paddingRight, toolbar.paddingBottom)
            scroll.setPadding(bars.left, scroll.paddingTop, bars.right, bars.bottom)
            WindowInsetsCompat.Builder(windowInsets)
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.NONE)
                .build()
        }
    }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 18f
        setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, 0xFF1D1B20.toInt()))
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF49454F.toInt()))
    }

    private fun margin(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

internal class FullyExpandedRecyclerView(context: Context) : RecyclerView(context) {
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val expandedHeight = View.MeasureSpec.makeMeasureSpec(Int.MAX_VALUE shr 2, View.MeasureSpec.AT_MOST)
        super.onMeasure(widthSpec, expandedHeight)
    }
}
