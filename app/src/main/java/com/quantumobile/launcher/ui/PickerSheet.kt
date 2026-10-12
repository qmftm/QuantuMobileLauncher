package com.quantumobile.launcher.ui

import android.view.LayoutInflater
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.quantumobile.launcher.R
import com.quantumobile.launcher.databinding.SheetPickerBinding

/** A filter chip; [matches] decides which rows it shows. Checked chips are OR-ed together. */
data class PickerFilter(val label: String, val checked: Boolean, val matches: (Row) -> Boolean)

/** Bottom sheet with optional search box and filter chips for picking one [Row]. */
object PickerSheet {
    fun show(
        fragment: Fragment,
        title: String,
        rows: List<Row>,
        searchable: Boolean = rows.size > 8,
        filters: List<PickerFilter> = emptyList(),
        onPick: (Row) -> Unit,
    ) {
        val ctx = fragment.requireContext()
        val dialog = BottomSheetDialog(ctx)
        val b = SheetPickerBinding.inflate(LayoutInflater.from(ctx))
        dialog.setContentView(b.root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true

        b.title.text = title
        b.searchLayout.isVisible = searchable
        b.filters.isVisible = filters.isNotEmpty()
        if (rows.size <= 6) b.list.layoutParams.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT

        val adapter = SimpleListAdapter(fragment.viewLifecycleOwner.lifecycleScope, showMore = false, onClick = { row ->
            dialog.dismiss()
            onPick(row)
        })
        b.list.layoutManager = LinearLayoutManager(ctx)
        b.list.adapter = adapter

        val chips = filters.map { f ->
            Chip(ctx).apply {
                text = f.label
                isCheckable = true
                isChecked = f.checked
                b.filters.addView(this)
            }
        }

        fun refresh() {
            val query = b.search.text?.toString()?.trim().orEmpty()
            val active = filters.filterIndexed { i, _ -> chips[i].isChecked }
            val shown = rows.filter { row ->
                (query.isEmpty() || row.title.contains(query, ignoreCase = true)) &&
                    (filters.isEmpty() || active.any { it.matches(row) })
            }
            adapter.submitList(shown)
            b.empty.isVisible = shown.isEmpty()
            b.empty.setText(R.string.no_results)
        }
        chips.forEach { it.setOnCheckedChangeListener { _, _ -> refresh() } }
        b.search.doAfterTextChanged { refresh() }
        refresh()
        dialog.show()
    }

}
