package com.azhilin.rokid.displaybridge.companion

import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

internal class FrameFavoriteAdapter(
    private val onApply: (FramePlacementFavorite) -> Unit,
    private val onDelete: (FramePlacementFavorite) -> Unit,
    private val onDragStart: (FavoriteViewHolder) -> Unit,
) : RecyclerView.Adapter<FrameFavoriteAdapter.FavoriteViewHolder>() {
    private val items = mutableListOf<FramePlacementFavorite>()

    init { setHasStableIds(true) }

    fun submitList(values: List<FramePlacementFavorite>) {
        items.clear()
        items.addAll(values)
        notifyDataSetChanged()
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
                addView(MaterialButton(parent.context).apply {
                    text = "Apply"
                    isAllCaps = false
                    setOnClickListener { bound()?.let(onApply) }
                })
                addView(MaterialButton(parent.context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "Delete"
                    isAllCaps = false
                    setOnClickListener { bound()?.let(onDelete) }
                })
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
        }

        private fun bound(): FramePlacementFavorite? =
            bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let(items::get)

        private fun dp(value: Int) = (value * itemView.resources.displayMetrics.density).toInt()
    }
}

private fun ViewGroup.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
