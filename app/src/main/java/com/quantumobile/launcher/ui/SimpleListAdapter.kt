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

/** Buttons at the right end of a row. */
enum class RowAction { DOWNLOAD, ENABLE, DISABLE, REMOVE }

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
    /** Remote icon (e.g. a mod icon); takes precedence over [icon]. */
    val iconUrl: String? = null,
    /** Buttons shown at the right end (at most two: first is the main one). */
    val actions: List<RowAction> = emptyList(),
    /** Shows a spinner instead of the buttons while something is being done for this row. */
    val busy: Boolean = false,
)

class SimpleListAdapter(
    private val scope: LifecycleCoroutineScope? = null,
    showMore: Boolean = true,
    private val onClick: (Row) -> Unit,
    private val onMore: (Row, View) -> Unit = { _, _ -> },
    private val onAction: (Row, RowAction) -> Unit = { _, _ -> },
) : ListAdapter<Row, SimpleListAdapter.Holder>(DIFF) {

    var showMore: Boolean = showMore
        set(value) {
            if (field == value) return
            field = value
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

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
        bindActions(b, row)
        b.root.setOnClickListener { onClick(row) }
        b.root.setOnLongClickListener { if (showMore) onMore(row, b.more); showMore }

        if (row.iconUrl != null && scope != null) {
            b.icon.setPadding(0, 0, 0, 0)
            b.icon.imageTintList = null
            RemoteImage.load(b.icon, row.iconUrl, scope, R.drawable.ic_extension, maxAgeHours = 24 * 7)
        } else if (row.avatar != null && scope != null) {
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

    private fun bindActions(b: ItemRowBinding, row: Row) {
        val ctx = b.root.context
        b.busy.isVisible = row.busy
        val buttons = listOf(b.action1, b.action2)
        buttons.forEachIndexed { i, button ->
            val action = row.actions.getOrNull(i)?.takeIf { !row.busy }
            button.isVisible = action != null
            if (action == null) return@forEachIndexed
            val (icon, label) = when (action) {
                RowAction.DOWNLOAD -> R.drawable.ic_download to R.string.download
                RowAction.ENABLE -> R.drawable.ic_toggle_off to R.string.visual_enable
                RowAction.DISABLE -> R.drawable.ic_toggle_on to R.string.visual_disable
                RowAction.REMOVE -> R.drawable.ic_delete to R.string.remove
            }
            button.setImageResource(icon)
            button.contentDescription = ctx.getString(label)
            // Download and "on" are the highlighted states; "off" and remove stay quiet.
            val tint = if (action == RowAction.DOWNLOAD || action == RowAction.DISABLE) R.color.accent else R.color.text_secondary
            button.imageTintList = android.content.res.ColorStateList.valueOf(ctx.getColor(tint))
            button.setOnClickListener { onAction(row, action) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(a: Row, b: Row) = a.id == b.id
            override fun areContentsTheSame(a: Row, b: Row) = a == b
        }
    }
}
