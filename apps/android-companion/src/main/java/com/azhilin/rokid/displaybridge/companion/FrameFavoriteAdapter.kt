package com.azhilin.rokid.displaybridge.companion

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.azhilin.rokid.displaybridge.protocol.DisplayLayoutPayload
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors

internal class FrameFavoriteAdapter(
    private val onApply: (FramePlacementFavorite) -> Unit,
    private val onEdit: (FramePlacementFavorite) -> Unit,
    private val onDelete: (FramePlacementFavorite) -> Unit,
    private val onDragStart: (FavoriteViewHolder) -> Unit,
) : RecyclerView.Adapter<FrameFavoriteAdapter.FavoriteViewHolder>() {
    private val items = mutableListOf<FramePlacementFavorite>()
    private var currentLayout: DisplayLayoutPayload? = null

    init { setHasStableIds(true) }

    fun submitList(values: List<FramePlacementFavorite>, layout: DisplayLayoutPayload? = currentLayout) {
        items.clear()
        items.addAll(values)
        currentLayout = layout?.clamped()
        notifyDataSetChanged()
    }

    fun setCurrentLayout(value: DisplayLayoutPayload) {
        val effective = value.clamped()
        if (currentLayout == effective) return
        val previous = currentLayout
        currentLayout = effective
        items.forEachIndexed { index, favorite ->
            val wasSelected = previous?.let(favorite::matchesLayout) == true
            val isSelected = favorite.matchesLayout(effective)
            if (wasSelected != isSelected) notifyItemChanged(index)
        }
    }

    fun move(from: Int, to: Int): FramePlacementFavorite? {
        if (from !in items.indices || to !in items.indices || from == to) return null
        val item = items.removeAt(from)
        items.add(to, item)
        notifyItemMoved(from, to)
        return item
    }

    override fun getItemId(position: Int): Long = items[position].id
    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FavoriteViewHolder =
        FavoriteViewHolder(parent)

    override fun onBindViewHolder(holder: FavoriteViewHolder, position: Int) {
        holder.bind(items[position])
    }

    inner class FavoriteViewHolder(parent: ViewGroup) : RecyclerView.ViewHolder(
        MaterialCardView(parent.context).apply {
            radius = parent.dp(18).toFloat()
            isCheckable = true
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = parent.dp(8) }
        },
    ) {
        private val name = TextView(parent.context).apply {
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
        }
        private val detail = TextView(parent.context).apply { textSize = 13f; alpha = .72f }
        private val drag = MaterialButton(parent.context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "☰"
            minWidth = dp(48)
            contentDescription = "Drag favorite"
        }
        private val applyAction = iconButton(R.drawable.ic_apply, "Apply favorite").apply {
            setOnClickListener { bound()?.let(onApply) }
        }
        private val editAction = iconButton(R.drawable.ic_edit, "Edit favorite").apply {
            setOnClickListener { bound()?.let(onEdit) }
        }
        private val deleteAction = iconButton(R.drawable.ic_delete, "Delete favorite").apply {
            setOnClickListener { bound()?.let(onDelete) }
        }
        private val actionButtons get() = listOf(applyAction, editAction, deleteAction)

        init {
            (itemView as MaterialCardView).addView(LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                addView(drag, LinearLayout.LayoutParams(dp(52), dp(52)))
                addView(LinearLayout(parent.context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(10), 0, dp(8), 0)
                    addView(name)
                    addView(detail)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(applyAction, LinearLayout.LayoutParams(dp(48), dp(48)))
                addView(editAction, LinearLayout.LayoutParams(dp(48), dp(48)))
                addView(deleteAction, LinearLayout.LayoutParams(dp(48), dp(48)))
            })
            drag.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) onDragStart(this)
                false
            }
        }

        fun bind(value: FramePlacementFavorite) {
            name.text = value.name
            detail.text = "Scale ${value.layout.scalePermille} · X ${value.layout.offsetXPermille} · Y ${value.layout.offsetYPermille}"
            drag.contentDescription = "Drag ${value.name}"
            val selected = currentLayout?.let(value::matchesLayout) == true
            val foreground = MaterialColors.getColor(
                itemView,
                if (selected) com.google.android.material.R.attr.colorOnPrimary
                else com.google.android.material.R.attr.colorOnSurface,
                if (selected) Color.WHITE else 0xFF1D1B20.toInt(),
            )
            val actionColor = if (selected) foreground else MaterialColors.getColor(
                itemView,
                androidx.appcompat.R.attr.colorPrimary,
                0xFF536BAF.toInt(),
            )
            name.setTextColor(foreground)
            detail.setTextColor(foreground)
            drag.apply {
                setTextColor(actionColor)
                strokeColor = ColorStateList.valueOf(actionColor)
                backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            }
            actionButtons.forEach { button ->
                button.iconTint = ColorStateList.valueOf(actionColor)
                button.strokeColor = ColorStateList.valueOf(actionColor)
                button.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            }
            (itemView as MaterialCardView).apply {
                isChecked = selected
                isSelected = selected
                strokeWidth = dp(if (selected) 2 else 1)
                strokeColor = MaterialColors.getColor(
                    this,
                    if (selected) androidx.appcompat.R.attr.colorPrimary
                    else com.google.android.material.R.attr.colorOutlineVariant,
                    if (selected) 0xFF536BAF.toInt() else 0xFFCAC4D0.toInt(),
                )
                setCardBackgroundColor(MaterialColors.getColor(
                    this,
                    if (selected) androidx.appcompat.R.attr.colorPrimary
                    else com.google.android.material.R.attr.colorSurfaceContainer,
                    if (selected) 0xFF536BAF.toInt() else 0xFFF3EDF7.toInt(),
                ))
                contentDescription = if (selected) "${value.name}, selected" else value.name
            }
        }

        private fun bound(): FramePlacementFavorite? =
            bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let(items::get)

        private fun iconButton(icon: Int, description: String) =
            MaterialButton(itemView.context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = null
                setIconResource(icon)
                iconSize = dp(22)
                iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                iconPadding = 0
                gravity = Gravity.CENTER
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                insetTop = 0
                insetBottom = 0
                setPadding(0, 0, 0, 0)
                contentDescription = description
            }

        private fun dp(value: Int) = (value * itemView.resources.displayMetrics.density).toInt()
    }
}

private fun ViewGroup.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
