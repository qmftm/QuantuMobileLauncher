package com.quantumobile.launcher.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.quantumobile.launcher.databinding.ItemRowBinding

data class Row(val id: String, val title: String, val subtitle: String, val selected: Boolean = false)

class SimpleListAdapter(
    private val onClick: (Row) -> Unit,
    private val onLongClick: (Row) -> Unit,
) : RecyclerView.Adapter<SimpleListAdapter.Holder>() {
    private var rows: List<Row> = emptyList()

    fun submit(newRows: List<Row>) {
        rows = newRows
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    class Holder(val binding: ItemRowBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        holder.binding.title.text = row.title
        holder.binding.subtitle.text = row.subtitle
        holder.binding.check.visibility = if (row.selected) android.view.View.VISIBLE else android.view.View.INVISIBLE
        holder.binding.root.setOnClickListener { onClick(row) }
        holder.binding.root.setOnLongClickListener { onLongClick(row); true }
    }
}
