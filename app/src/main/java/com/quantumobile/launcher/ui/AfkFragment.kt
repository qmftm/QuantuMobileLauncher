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
import com.quantumobile.launcher.databinding.ItemIconChoiceBinding
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** "잠수" tab: the AFK servers, each with an on/off switch and its live state. */
class AfkFragment : Fragment() {
    private var _binding: FragmentListBinding? = null
    private val binding get() = _binding!!
    private val app get() = requireActivity().application as QuantumApp
    private lateinit var adapter: SimpleListAdapter

    /** Where a picked gallery image goes (set while the edit dialog is open). */
    private var onImagePicked: ((android.net.Uri) -> Unit)? = null
    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onImagePicked?.invoke(uri)
    }

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
                AfkPhase.CONNECTED -> R.color.success
                AfkPhase.ERROR -> R.color.error
                AfkPhase.CONNECTING, AfkPhase.WAITING -> R.color.warning
                AfkPhase.OFF -> R.color.text_secondary
            }
            val file = AfkIcons.file(ctx, server.id, server.icon)
            Row(
                id = server.id,
                title = server.name,
                subtitle = "${server.address} · $accountName\n${status.describe(ctx)}",
                subtitleLines = 3,
                badge = status.label(ctx),
                icon = (AfkIcons.preset(server.icon) ?: AfkIcons.fallback).res,
                iconRaw = true,
                iconFile = file?.path,
                iconStamp = file?.lastModified() ?: 0,
                statusColor = ctx.getColor(color),
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

        // Icon: chosen in a picker; a gallery image is kept as a pending file until Save.
        val id = existing?.id ?: java.util.UUID.randomUUID().toString()
        var icon = existing?.icon
        var pendingImage = false
        fun showIcon() {
            val ctx = requireContext()
            val pending = AfkIcons.pendingFile(ctx, id)
            val drawable = if (pendingImage && pending.isFile) {
                android.graphics.BitmapFactory.decodeFile(pending.path)?.let { AfkIcons.pixelated(ctx, it) }
            } else null
            b.iconPreview.setImageDrawable(drawable ?: AfkIcons.drawable(ctx, id, icon))
            b.iconName.text = when {
                icon == null -> getString(R.string.afk_icon_server)
                icon == AfkServer.ICON_CUSTOM -> getString(R.string.afk_icon_custom)
                else -> AfkIcons.preset(icon)?.let { getString(it.label) } ?: getString(R.string.afk_icon_server)
            }
        }
        showIcon()
        onImagePicked = { uri ->
            viewLifecycleOwner.lifecycleScope.launch {
                val ctx = requireContext().applicationContext
                val ok = withContext(Dispatchers.IO) { AfkIcons.importImage(ctx, uri, AfkIcons.pendingFile(ctx, id)) }
                if (ok) { icon = AfkServer.ICON_CUSTOM; pendingImage = true; showIcon() }
                else Snackbar.make(b.root, R.string.afk_icon_import_failed, Snackbar.LENGTH_SHORT).show()
            }
        }
        val openPicker = View.OnClickListener {
            pickIcon(id, icon, hasCustom = pendingImage || AfkIcons.customFile(requireContext(), id).isFile) { choice ->
                if (choice == PICK_IMAGE) {
                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                } else {
                    icon = choice
                    showIcon()
                }
            }
        }
        b.iconRow.setOnClickListener(openPicker)
        b.iconChange.setOnClickListener(openPicker)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setOnDismissListener {
                onImagePicked = null
                AfkIcons.pendingFile(requireContext(), id).delete()
            }
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
            val server = (existing ?: AfkServer(id = id, name = name, host = host, accountId = accounts[accountIndex].id))
                .copy(name = name, host = host, port = realPort, accountId = accounts[accountIndex].id, icon = icon)
            val ctx = requireContext()
            val pending = AfkIcons.pendingFile(ctx, id)
            if (icon == AfkServer.ICON_CUSTOM && pendingImage && pending.isFile) {
                val target = AfkIcons.customFile(ctx, id)
                target.delete()
                pending.renameTo(target)
            }
            pending.delete()
            app.afk.upsert(server)
            // Fetch the server's own icon now, so it shows before the bot is ever switched on.
            if (icon == null) app.fetchAfkServerIcon(server) { if (_binding != null) refresh() }
            dialog.dismiss()
        }
    }

    private fun confirmDelete(server: AfkServer) {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(getString(R.string.confirm_remove, server.name))
            .setPositiveButton(R.string.remove) { _, _ ->
                app.afk.remove(server.id)
                AfkIcons.deleteAll(requireContext(), server.id)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Grid of icon choices: the server's own icon, the presets, a previously picked image, and "add image". */
    private fun pickIcon(id: String, current: String?, hasCustom: Boolean, onPick: (String?) -> Unit) {
        val ctx = requireContext()
        class Choice(val key: String?, val label: String, val drawable: android.graphics.drawable.Drawable)
        val choices = buildList {
            add(Choice(null, getString(R.string.afk_icon_server), AfkIcons.drawable(ctx, id, null)))
            if (hasCustom) {
                val pending = AfkIcons.pendingFile(ctx, id)
                val img = (if (pending.isFile) pending else AfkIcons.customFile(ctx, id))
                    .let { android.graphics.BitmapFactory.decodeFile(it.path) }
                if (img != null) add(Choice(AfkServer.ICON_CUSTOM, getString(R.string.afk_icon_custom), AfkIcons.pixelated(ctx, img)))
            }
            AfkIcons.presets.forEach {
                add(Choice("${AfkServer.ICON_PRESET}${it.key}", getString(it.label), androidx.core.content.ContextCompat.getDrawable(ctx, it.res)!!))
            }
            add(Choice(PICK_IMAGE, getString(R.string.afk_icon_add), androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.ic_add)!!))
        }
        val grid = androidx.recyclerview.widget.RecyclerView(ctx).apply {
            layoutManager = androidx.recyclerview.widget.GridLayoutManager(ctx, 4)
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
        }
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.afk_icon_pick)
            .setView(grid)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        grid.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            override fun getItemCount() = choices.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                object : androidx.recyclerview.widget.RecyclerView.ViewHolder(
                    ItemIconChoiceBinding.inflate(LayoutInflater.from(parent.context), parent, false).root,
                ) {}
            override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                val c = choices[position]
                val v = ItemIconChoiceBinding.bind(holder.itemView)
                v.image.setImageDrawable(c.drawable)
                v.image.imageTintList = if (c.key == PICK_IMAGE) android.content.res.ColorStateList.valueOf(ctx.getColor(R.color.brand)) else null
                v.image.strokeWidth = if (c.key == current && c.key != PICK_IMAGE) 3 * resources.displayMetrics.density else 0f
                v.label.text = c.label
                holder.itemView.setOnClickListener { dialog.dismiss(); onPick(c.key) }
            }
        }
        dialog.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        const val PICK_IMAGE = "pick-image"
    }
}
