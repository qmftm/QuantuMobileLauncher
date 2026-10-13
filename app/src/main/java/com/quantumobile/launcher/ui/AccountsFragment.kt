package com.quantumobile.launcher.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.quantumobile.launcher.QuantumApp
import com.quantumobile.launcher.R
import com.quantumobile.launcher.core.auth.Account
import com.quantumobile.launcher.core.auth.AccountType
import com.quantumobile.launcher.core.auth.MicrosoftAuth
import com.quantumobile.launcher.databinding.FragmentListBinding
import kotlinx.coroutines.launch

class AccountsFragment : Fragment() {
    private var _binding: FragmentListBinding? = null
    private val binding get() = _binding!!
    private val store get() = (requireActivity().application as QuantumApp).accounts
    private lateinit var adapter: SimpleListAdapter

    private val msLogin = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val code = result.data?.getStringExtra(MicrosoftLoginActivity.EXTRA_CODE)
        if (result.resultCode == Activity.RESULT_OK && code != null) finishMicrosoftLogin(code)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = SimpleListAdapter(
            viewLifecycleOwner.lifecycleScope,
            onClick = { row -> store.select(row.id); refresh(); snack(getString(R.string.account_selected, row.title)) },
            onMore = ::showMenu,
        )
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter
        binding.emptyIcon.setImageResource(R.drawable.ic_person)
        binding.emptyText.setText(R.string.accounts_empty)
        binding.primaryAction.setText(R.string.add_microsoft)
        binding.secondaryAction.setText(R.string.add_offline)
        binding.primaryAction.setOnClickListener { msLogin.launch(Intent(requireContext(), MicrosoftLoginActivity::class.java)) }
        binding.secondaryAction.setOnClickListener { addOffline() }
        binding.list.addOnScrollListener(shrinkFabsOnScroll(binding))

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                store.accounts.collect { refresh() }
            }
        }
    }

    private fun refresh() {
        val accounts = store.accounts.value
        val selected = store.selected?.id
        binding.empty.isVisible = accounts.isEmpty()
        adapter.submitList(accounts.map {
            Row(
                it.id, it.username,
                getString(if (it.type == AccountType.MICROSOFT) R.string.account_microsoft else R.string.account_offline),
                selected = it.id == selected,
                badge = if (it.type == AccountType.MICROSOFT) "MS" else null,
                avatar = it,
            )
        })
    }

    private fun showMenu(row: Row, anchor: View) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add(0, 1, 0, R.string.select)
            menu.add(0, 2, 1, R.string.remove)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> { store.select(row.id); refresh() }
                    2 -> confirmDelete(row)
                }
                true
            }
        }.show()
    }

    private fun addOffline() {
        val layout = TextInputLayout(requireContext(), null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = getString(R.string.player_name)
            helperText = getString(R.string.name_rules)
            counterMaxLength = 16
            isCounterEnabled = true
        }
        val input = TextInputEditText(layout.context).apply { isSingleLine = true }
        layout.addView(input)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.add_offline)
            .setView(layout.withPadding())
            .setPositiveButton(R.string.add, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val name = input.text.toString().trim()
            if (Account.isValidOfflineName(name)) {
                store.upsert(Account.offline(name))
                dialog.dismiss()
            } else {
                layout.error = getString(R.string.invalid_name)
            }
        }
        input.requestFocus()
    }

    private fun finishMicrosoftLogin(code: String) {
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.signing_in)
            .setCancelable(false)
            .show()
        lifecycleScope.launch {
            try {
                val account = MicrosoftAuth.loginWithCode(code)
                store.upsert(account)
                snack(getString(R.string.welcome, account.username))
            } catch (e: Exception) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.login_failed)
                    .setMessage(e.message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            } finally {
                dialog.dismiss()
            }
        }
    }

    private fun confirmDelete(row: Row) {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(getString(R.string.confirm_remove, row.title))
            .setPositiveButton(R.string.remove) { _, _ -> store.remove(row.id) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun snack(text: String) = Snackbar.make(binding.root, text, Snackbar.LENGTH_SHORT).show()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

internal fun View.withPadding(): View {
    val pad = (20 * resources.displayMetrics.density).toInt()
    return android.widget.FrameLayout(context).also { frame ->
        frame.setPadding(pad, pad / 2, pad, 0)
        frame.addView(this)
    }
}

/** Collapses the extended FABs to icons while scrolling down, like most Material apps. */
internal fun shrinkFabsOnScroll(binding: FragmentListBinding) = object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
    override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
        listOf(binding.primaryAction, binding.secondaryAction).forEach { if (dy > 0) it.shrink() else if (dy < 0) it.extend() }
    }
}
