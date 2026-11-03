package com.quantumobile.launcher.ui

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.quantumobile.launcher.QuantumApp
import com.quantumobile.launcher.R
import com.quantumobile.launcher.core.afk.AfkPhase
import com.quantumobile.launcher.core.afk.AfkServer
import com.quantumobile.launcher.core.afk.AfkStatus
import com.quantumobile.launcher.core.auth.AccountType
import com.quantumobile.launcher.databinding.DialogAfkServerBinding
import com.quantumobile.launcher.databinding.FragmentListBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** "잠수" tab: the AFK servers, each with an on/off switch and its live state. */
class AfkFragment : Fragment() {
    private var _binding: FragmentListBinding? = null
    private val binding get() = _binding!!
    private val app get() = requireActivity().application as QuantumApp
    private lateinit var adapter: SimpleListAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = SimpleListAdapter(
            viewLifecycleOwner.lifecycleScope,
            onClick = { row -> app.afk.servers.value.firstOrNull { it.id == row.id }?.let(::editServer) },
            onMore = ::showMenu,
            onAction = { row, action -> toggle(row.id, action == RowAction.ENABLE) },
        )
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter
        binding.emptyIcon.setImageResource(R.drawable.ic_afk)
        binding.emptyText.setText(R.string.afk_empty)
        binding.primaryAction.setText(R.string.afk_add_server)
        binding.primaryAction.setOnClickListener { editServer(null) }
        binding.secondaryAction.isVisible = false
        binding.list.addOnScrollListener(shrinkFabsOnScroll(binding))

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(app.afk.servers, app.afk.statuses, app.accounts.accounts) { _, _, _ -> }.collect { refresh() }
                }
                launch {
                    // Keeps the "retry in N s" countdown moving.
                    while (true) {
                        delay(1000)
                        if (app.afk.statuses.value.values.any { it.phase == AfkPhase.WAITING }) refresh()
                    }
                }
            }
        }
    }

    private fun refresh() {
        val servers = app.afk.servers.value
        val accounts = app.accounts.accounts.value.associateBy { it.id }
        binding.empty.isVisible = servers.isEmpty()
        val ctx = requireContext()
        adapter.submitList(servers.map { server ->
            val status = if (server.enabled) app.afk.status(server.id) else AfkStatus.OFF
            val accountName = accounts[server.accountId]?.username ?: getString(R.string.afk_no_account)
            val color = when (status.phase) {
                AfkPhase.CONNECTED -> R.color.accent
                AfkPhase.ERROR -> R.color.error
                AfkPhase.CONNECTING, AfkPhase.WAITING -> R.color.warning
                AfkPhase.OFF -> R.color.text_secondary
            }
            Row(
                id = server.id,
                title = server.name,
                subtitle = "${server.address} · $accountName\n${status.describe(ctx)}",
                subtitleLines = 3,
                selected = status.phase == AfkPhase.CONNECTED,
                badge = status.label(ctx),
                icon = R.drawable.ic_afk,
                iconTint = ctx.getColor(color),
                actions = listOf(if (server.enabled) RowAction.DISABLE else RowAction.ENABLE),
            )
        })
    }

    private fun toggle(id: String, on: Boolean) {
        app.afk.setEnabled(id, on)
        if (on) {
            app.startAfk()
            maybeAskBatteryExemption()
        }
    }

    /** The first time a server is switched on, offers to lift battery optimisation (otherwise Android may cut the connection). */
    private fun maybeAskBatteryExemption() {
        val ctx = requireContext()
        if (BatteryOptimization.isIgnoring(ctx)) return
        val prefs = ctx.getSharedPreferences("launcher", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("asked_afk_battery", false)) return
        prefs.edit().putBoolean("asked_afk_battery", true).apply()
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.afk_battery_title)
            .setMessage(R.string.afk_battery_message)
            .setPositiveButton(R.string.afk_battery_allow) { _, _ -> BatteryOptimization.request(ctx) }
            .setNegativeButton(R.string.afk_later, null)
            .show()
    }

    private fun showMenu(row: Row, anchor: View) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add(0, 1, 0, R.string.afk_edit)
            menu.add(0, 2, 1, R.string.remove)
            setOnMenuItemClickListener { item ->
                val server = app.afk.servers.value.firstOrNull { it.id == row.id } ?: return@setOnMenuItemClickListener true
                when (item.itemId) {
                    1 -> editServer(server)
                    2 -> confirmDelete(server)
                }
                true
            }
        }.show()
    }

    @SuppressLint("SetTextI18n") // port numbers are not localised
    private fun editServer(existing: AfkServer?) {
        val accounts = app.accounts.accounts.value
        if (accounts.isEmpty()) {
            Snackbar.make(binding.root, R.string.afk_need_account, Snackbar.LENGTH_LONG)
                .setAction(R.string.nav_accounts) { (activity as? MainActivity)?.navigateTo(R.id.nav_accounts) }
                .show()
            return
        }
        val b = DialogAfkServerBinding.inflate(layoutInflater)
        val labels = accounts.map {
            "${it.username} (${getString(if (it.type == AccountType.MICROSOFT) R.string.account_microsoft else R.string.account_offline)})"
        }
        b.account.setSimpleItems(labels.toTypedArray())
        var accountIndex = accounts.indexOfFirst { it.id == existing?.accountId }
            .takeIf { it >= 0 } ?: accounts.indexOfFirst { it.id == app.accounts.selected?.id }.coerceAtLeast(0)
        b.account.setText(labels[accountIndex], false)
        b.account.setOnItemClickListener { _, _, position, _ -> accountIndex = position }
        if (existing != null) {
            b.name.setText(existing.name)
            b.address.setText(existing.host)
            b.port.setText(existing.port.toString())
        } else {
            b.port.setText(AfkServer.DEFAULT_PORT.toString())
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (existing == null) R.string.afk_add_server else R.string.afk_edit_server)
            .setView(b.root)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            b.addressLayout.error = null
            b.portLayout.error = null
            val port = b.port.text.toString().trim().ifEmpty { AfkServer.DEFAULT_PORT.toString() }.toIntOrNull()
            if (port == null || port !in 1..65535) {
                b.portLayout.error = getString(R.string.afk_bad_port)
                return@setOnClickListener
            }
            // "host:port" typed into the address box wins over the port box.
            val parsed = AfkServer.parseAddress(b.address.text.toString(), port)
            if (parsed == null) {
                b.addressLayout.error = getString(R.string.afk_bad_address)
                return@setOnClickListener
            }
            val (host, realPort) = parsed
            val name = b.name.text.toString().trim().ifEmpty { host }
            val server = (existing ?: AfkServer(name = name, host = host, accountId = accounts[accountIndex].id))
                .copy(name = name, host = host, port = realPort, accountId = accounts[accountIndex].id)
            app.afk.upsert(server)
            dialog.dismiss()
        }
    }

    private fun confirmDelete(server: AfkServer) {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(getString(R.string.confirm_remove, server.name))
            .setPositiveButton(R.string.remove) { _, _ -> app.afk.remove(server.id) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
