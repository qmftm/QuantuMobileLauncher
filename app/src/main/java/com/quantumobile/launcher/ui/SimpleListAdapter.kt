package com.quantumobile.launcher.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.quantumobile.launcher.R
import com.quantumobile.launcher.core.auth.Account
import com.quantumobile.launcher.databinding.ItemRowBinding

data class Row(
    val id: String,
    val title: String,
    val subtitle: String,
    val selected: Boolean = false,
    val badge: String? = null,
    @DrawableRes val icon: Int = R.drawable.ic_cube,
    val iconTint: Int? = null,
    /** When set, the icon shows this account's skin head instead of [icon]. */
    val avatar: Account? = null,
)

class SimpleListAdapter(
    private val scope: LifecycleCoroutineScope? = null,
    private val showMore: Boolean = true,
    private val onClick: (Row) -> Unit,
    private val onMore: (Row, View) -> Unit = { _, _ -> },
) : ListAdapter<Row, SimpleListAdapter.Holder>(DIFF) {

    class Holder(val binding: ItemRowBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        val b = holder.binding
        val ctx = b.root.context
        b.title.text = row.title
        b.subtitle.text = row.subtitle
        b.subtitle.isVisible = row.subtitle.isNotEmpty()
        b.badge.isVisible = row.badge != null
        b.badge.text = row.badge
        b.check.isVisible = row.selected
        b.card.strokeColor = ctx.getColor(if (row.selected) R.color.brand else R.color.outline)
        b.more.isVisible = showMore
        b.more.setOnClickListener { onMore(row, it) }
        b.root.setOnClickListener { onClick(row) }
        b.root.setOnLongClickListener { if (showMore) onMore(row, b.more); showMore }

        if (row.avatar != null && scope != null) {
            b.icon.setPadding(0, 0, 0, 0)
            b.icon.imageTintList = null
            AvatarLoader.load(b.icon, row.avatar, scope)
        } else {
            val pad = (10 * ctx.resources.displayMetrics.density).toInt()
            b.icon.setPadding(pad, pad, pad, pad)
            b.icon.setImageResource(row.icon)
            b.icon.imageTintList = android.content.res.ColorStateList.valueOf(row.iconTint ?: ctx.getColor(R.color.accent))
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(a: Row, b: Row) = a.id == b.id
            override fun areContentsTheSame(a: Row, b: Row) = a == b
        }
    }
}
