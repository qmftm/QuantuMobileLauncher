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
import com.quantumobile.launcher.core.afk.remote.AfkHost
import com.quantumobile.launcher.core.afk.remote.BadCodeException
import com.quantumobile.launcher.core.afk.remote.Ops
import com.quantumobile.launcher.core.afk.remote.RemoteProtocol
import com.quantumobile.launcher.core.afk.remote.RemoteRequest
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.withTimeout
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
            onClick = ::open,
            onMore = ::showMenu,
            onAction = { row, action -> toggle(row.id, action == RowAction.ENABLE) },
        )
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter
        binding.emptyIcon.setImageResource(R.drawable.ic_afk)
        binding.emptyText.setText(R.string.afk_empty)
        binding.primaryAction.setText(R.string.afk_add_server)
        binding.primaryAction.setOnClickListener { editServer(null, null) }
        binding.secondaryAction.setText(R.string.afk_pcs)
        binding.secondaryAction.setIconResource(R.drawable.ic_computer)
        binding.secondaryAction.setOnClickListener { managePcs() }
        binding.list.addOnScrollListener(shrinkFabsOnScroll(binding))

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(app.afk.servers, app.afk.statuses, app.accounts.accounts, remote.hosts, remote.states) { _, _, _, _, _ -> }
                        .collect { refresh() }
                }
                launch {
                    // PCs are asked for their servers while this screen is visible.
                    while (true) {
                        if (remote.hosts.value.isNotEmpty()) remote.poll()
                        delay(POLL_MS)
                    }
                }
                launch {
                    // Keeps the "retry in N s" countdown moving.
                    while (true) {
                        delay(1000)
                        val waiting = app.afk.statuses.value.values.any { it.phase == AfkPhase.WAITING } ||
                            remote.states.value.values.any { st -> st.servers.orEmpty().any { it.phase == AfkPhase.WAITING } }
                        if (waiting) refresh()
                    }
                }
            }
        }
    }

    private val remote get() = app.afkRemote

    private fun statusColor(phase: AfkPhase) = requireContext().getColor(
        when (phase) {
            AfkPhase.CONNECTED -> R.color.success
            AfkPhase.ERROR -> R.color.error
            AfkPhase.CONNECTING, AfkPhase.WAITING -> R.color.warning
            AfkPhase.OFF -> R.color.text_secondary
        },
    )

    private fun refresh() {
        if (_binding == null) return
        val ctx = requireContext()
        val accounts = app.accounts.accounts.value.associateBy { it.id }
        val now = System.currentTimeMillis()
        val rows = mutableListOf<Row>()
        for (server in app.afk.servers.value) {
            val status = if (server.enabled) app.afk.status(server.id) else AfkStatus.OFF
            val accountName = accounts[server.accountId]?.username ?: getString(R.string.afk_no_account)
            val file = AfkIcons.file(ctx, server.id, server.icon)
            rows += Row(
                id = LOCAL + server.id,
                title = server.name,
                subtitle = "${server.address} · $accountName\n${status.describe(ctx, now)}",
                subtitleLines = 3,
                badge = status.label(ctx),
                icon = (AfkIcons.preset(server.icon) ?: AfkIcons.fallback).res,
                iconRaw = true,
                iconFile = file?.path,
                iconStamp = file?.lastModified() ?: 0,
                statusColor = statusColor(status.phase),
                actions = listOf(if (server.enabled) RowAction.DISABLE else RowAction.ENABLE),
            )
        }
        for (host in remote.hosts.value) {
            val state = remote.states.value[host.id] ?: continue
            if (state.servers == null) {
                rows += Row(
                    id = HOST + host.id,
                    title = host.name,
                    subtitle = getString(R.string.afk_pc_offline, state.error.orEmpty()),
                    subtitleLines = 3,
                    badge = getString(R.string.afk_pc_offline_badge),
                    icon = AfkIcons.fallback.res,
                    iconRaw = true,
                    statusColor = ctx.getColor(R.color.error),
                )
                continue
            }
            for (rs in state.servers) {
                val status = AfkRemote.status(rs, now)
                val file = remote.iconFile(host.id, rs)
                rows += Row(
                    id = "$REMOTE${host.id}:${rs.server.id}",
                    title = rs.server.name,
                    subtitle = "${rs.server.address} · ${rs.accountName} · ${getString(R.string.afk_pc_row, host.name)}\n" +
                        status.describe(ctx, now) + if (state.error != null) "\n" + getString(R.string.afk_pc_reconnecting) else "",
                    subtitleLines = 4,
                    badge = status.label(ctx),
                    icon = (AfkIcons.preset(rs.server.icon) ?: AfkIcons.fallback).res,
                    iconRaw = true,
                    iconFile = file?.path,
                    iconStamp = file?.lastModified() ?: 0,
                    statusColor = statusColor(status.phase),
                    actions = listOf(if (rs.server.enabled) RowAction.DISABLE else RowAction.ENABLE),
                )
            }
        }
        binding.empty.isVisible = rows.isEmpty()
        adapter.submitList(rows)
    }

    /** A row's server: which one, and on which PC (null = this phone). */
    private data class Target(val server: AfkServer, val hostId: String?)

    private fun target(rowId: String): Target? = when {
        rowId.startsWith(LOCAL) -> app.afk.servers.value.firstOrNull { LOCAL + it.id == rowId }?.let { Target(it, null) }
        rowId.startsWith(REMOTE) -> {
            val (hostId, serverId) = rowId.removePrefix(REMOTE).split(':', limit = 2)
            remote.states.value[hostId]?.servers?.firstOrNull { it.server.id == serverId }?.let { Target(it.server, hostId) }
        }
        else -> null
    }

    /** Tapping a server opens its log (edit is in the ⋮ menu). */
    private fun open(row: Row) {
        if (row.id.startsWith(HOST)) return managePcs()
        val t = target(row.id) ?: return
        startActivity(AfkLogActivity.intent(requireContext(), t.server.id, t.hostId, t.server.name))
    }

    private fun toggle(rowId: String, on: Boolean) {
        val t = target(rowId) ?: return
        if (t.hostId == null) {
            app.afk.setEnabled(t.server.id, on)
            if (on) {
                app.startAfk()
                maybeAskBatteryExemption()
            }
            return
        }
        remoteAction(t.hostId, RemoteRequest(Ops.SET_ENABLED, 0, id = t.server.id, enabled = on))
    }

    /** Runs a request on a PC in the background; failures show as a snackbar. */
    private fun remoteAction(hostId: String, req: RemoteRequest, onDone: () -> Unit = {}) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                remote.call(hostId, req)
                onDone()
            } catch (e: Exception) {
                if (_binding != null) Snackbar.make(binding.root, AfkRemote.describe(e), Snackbar.LENGTH_LONG).show()
            }
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
        if (row.id.startsWith(HOST)) return managePcs()
        PopupMenu(requireContext(), anchor).apply {
            menu.add(0, 3, 0, R.string.afk_log)
            menu.add(0, 1, 1, R.string.afk_edit)
            menu.add(0, 2, 2, R.string.remove)
            setOnMenuItemClickListener { item ->
                val t = target(row.id) ?: return@setOnMenuItemClickListener true
                when (item.itemId) {
                    1 -> editServer(t.server, t.hostId)
                    2 -> confirmDelete(t)
                    3 -> open(row)
                }
                true
            }
        }.show()
    }

    @SuppressLint("SetTextI18n") // port numbers are not localised
    private fun editServer(existing: AfkServer?, existingHost: String?) {
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

        // Where it runs: this phone or one of the paired PCs.
        val hosts = remote.hosts.value
        val places = listOf(getString(R.string.afk_location_phone)) + hosts.map { getString(R.string.afk_location_pc, it.name) }
        var placeIndex = hosts.indexOfFirst { it.id == existingHost }.let { if (it < 0) 0 else it + 1 }
        b.locationLayout.isVisible = hosts.isNotEmpty()
        b.location.setSimpleItems(places.toTypedArray())
        b.location.setText(places[placeIndex], false)
        b.location.setOnItemClickListener { _, _, position, _ -> placeIndex = position }

        // Icon: chosen in a picker; a gallery image is kept as a pending file until Save.
        val id = existing?.id ?: java.util.UUID.randomUUID().toString()
        var icon = existing?.icon
        var pendingImage = false
        // A PC server's current image lives on the PC; show the copy this phone cached.
        val remoteIcon = existingHost?.let { h ->
            remote.states.value[h]?.servers?.firstOrNull { it.server.id == id }?.let { remote.iconFile(h, it) }
        }
        fun showIcon() {
            val ctx = requireContext()
            val pending = AfkIcons.pendingFile(ctx, id)
            val file = when {
                pendingImage && pending.isFile -> pending
                remoteIcon != null && icon == existing?.icon -> remoteIcon
                else -> null
            }
            val drawable = file?.let { f -> android.graphics.BitmapFactory.decodeFile(f.path)?.let { AfkIcons.pixelated(ctx, it) } }
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
            .create()
        // No keyboard on open: tapping a field shows it, and it must not cover the pickers below.
        dialog.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN or
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
        dialog.show()
        val saveButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
        saveButton.setOnClickListener {
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
            val account = accounts[accountIndex]
            val server = (existing ?: AfkServer(id = id, name = name, host = host, accountId = account.id))
                .copy(name = name, host = host, port = realPort, accountId = account.id, icon = icon)
            val newHost = if (placeIndex == 0) null else hosts[placeIndex - 1].id

            if (newHost == null) {
                saveOnPhone(server, pendingImage)
                if (existingHost != null) remoteAction(existingHost, RemoteRequest(Ops.REMOVE, 0, id = id))
                dialog.dismiss()
                return@setOnClickListener
            }
            confirmTokens(newHost, account.type == AccountType.MICROSOFT) {
                saveButton.isEnabled = false
                saveButton.setText(R.string.afk_saving)
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        saveOnPc(server, newHost, pendingImage, movedFromPhone = existing != null && existingHost == null)
                        if (existingHost != null && existingHost != newHost) {
                            runCatching { remote.call(existingHost, RemoteRequest(Ops.REMOVE, 0, id = id)) }
                        }
                        dialog.dismiss()
                    } catch (e: Exception) {
                        saveButton.isEnabled = true
                        saveButton.setText(R.string.save)
                        Snackbar.make(b.root, getString(R.string.afk_pc_failed, AfkRemote.describe(e)), Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun saveOnPhone(server: AfkServer, pendingImage: Boolean) {
        val ctx = requireContext()
        val pending = AfkIcons.pendingFile(ctx, server.id)
        if (server.icon == AfkServer.ICON_CUSTOM && pendingImage && pending.isFile) {
            val target = AfkIcons.customFile(ctx, server.id)
            target.delete()
            pending.renameTo(target)
        }
        pending.delete()
        app.afk.upsert(server)
        // Fetch the server's own icon now, so it shows before the bot is ever switched on.
        if (server.icon == null) app.fetchAfkServerIcon(server) { refresh() }
    }

    /** Sends [server] (with its account, refreshed first, and a picked image) to PC [hostId]. */
    private suspend fun saveOnPc(server: AfkServer, hostId: String, pendingImage: Boolean, movedFromPhone: Boolean) {
        val ctx = requireContext()
        val account = app.afkAccount(server.accountId)
        val image = when {
            server.icon != AfkServer.ICON_CUSTOM -> null
            pendingImage -> AfkIcons.pendingFile(ctx, server.id)
            movedFromPhone -> AfkIcons.customFile(ctx, server.id)
            else -> null
        }?.takeIf { it.isFile }?.let { java.util.Base64.getEncoder().encodeToString(it.readBytes()) }
        remote.call(hostId, RemoteRequest(Ops.UPSERT, 0, server = server, account = account, iconPng = image))
        if (movedFromPhone) {
            app.afk.remove(server.id)
            AfkIcons.deleteAll(ctx, server.id)
        }
        AfkIcons.pendingFile(ctx, server.id).delete()
        remote.poll()
    }

    /** Microsoft sign-in tokens go to a PC only after the user agreed once for that PC. */
    private fun confirmTokens(hostId: String, microsoft: Boolean, onOk: () -> Unit) {
        val prefs = requireContext().getSharedPreferences("launcher", android.content.Context.MODE_PRIVATE)
        val key = "afk_tokens_ok_$hostId"
        if (!microsoft || prefs.getBoolean(key, false)) return onOk()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.afk_pc_tokens_title)
            .setMessage(R.string.afk_pc_tokens_message)
            .setPositiveButton(R.string.afk_pc_tokens_ok) { _, _ ->
                prefs.edit().putBoolean(key, true).apply()
                onOk()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(t: Target) {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(getString(R.string.confirm_remove, t.server.name))
            .setPositiveButton(R.string.remove) { _, _ ->
                if (t.hostId != null) {
                    remoteAction(t.hostId, RemoteRequest(Ops.REMOVE, 0, id = t.server.id))
                } else {
                    app.afk.remove(t.server.id)
                    AfkIcons.deleteAll(requireContext(), t.server.id)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** The paired PCs: their state, forget one, or add one. */
    private fun managePcs() {
        val ctx = requireContext()
        val hosts = remote.hosts.value
        val builder = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.afk_pcs)
            .setPositiveButton(R.string.afk_pc_add) { _, _ -> addPc() }
            .setNegativeButton(android.R.string.cancel, null)
        if (hosts.isEmpty()) {
            builder.setMessage(R.string.afk_pc_none)
        } else {
            val items = hosts.map { h ->
                val st = remote.states.value[h.id]
                val line = when {
                    st?.servers != null -> getString(R.string.afk_pc_online, st.servers.size)
                    st?.error != null -> getString(R.string.afk_pc_offline, st.error)
                    else -> h.address.ifBlank { getString(R.string.afk_pc_checking) }
                }
                if (h.address.isBlank()) "${h.name}\n$line" else "${h.name}  (${h.address})\n$line"
            }
            builder.setItems(items.toTypedArray()) { _, which ->
                val h = hosts[which]
                MaterialAlertDialogBuilder(ctx)
                    .setMessage(getString(R.string.afk_pc_remove_confirm, h.name))
                    .setPositiveButton(R.string.remove) { _, _ -> remote.removeHost(h.id) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
        builder.show()
    }

    private fun addPc() {
        val ctx = requireContext()
        fun field(hint: Int, help: Int? = null, caps: Boolean = false): Pair<TextInputLayout, TextInputEditText> {
            val layout = TextInputLayout(ctx, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
                this.hint = getString(hint)
                if (help != null) helperText = getString(help)
            }
            val input = TextInputEditText(layout.context).apply {
                isSingleLine = true
                if (caps) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            }
            layout.addView(input)
            return layout to input
        }
        val (codeLayout, code) = field(R.string.afk_pc_code, R.string.afk_pc_code_help, caps = true)
        val (addrLayout, addr) = field(R.string.afk_pc_address, R.string.afk_pc_address_help)
        val (nameLayout, nameInput) = field(R.string.afk_pc_name)
        val box = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            val gap = (12 * resources.displayMetrics.density).toInt()
            listOf(codeLayout, addrLayout, nameLayout).forEachIndexed { i, field ->
                addView(field, android.widget.LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = gap })
            }
        }
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.afk_pc_add)
            .setView(box)
            .setPositiveButton(R.string.add, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        val ok = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
        ok.setOnClickListener {
            addrLayout.error = null; codeLayout.error = null
            val address = addr.text.toString().trim()
            val pairing = code.text.toString().trim()
            if (RemoteProtocol.normalize(pairing).length != 12) { codeLayout.error = getString(R.string.afk_pc_code_help); return@setOnClickListener }
            ok.isEnabled = false
            ok.setText(R.string.afk_pc_checking)
            viewLifecycleOwner.lifecycleScope.launch {
                val draft = AfkHost(name = nameInput.text.toString().trim(), address = address, code = pairing)
                try {
                    val found = withTimeout(40_000) { remote.check(draft) }
                    val host = draft.copy(name = draft.name.ifEmpty { found.info.name })
                    remote.saveHost(host, found.url)
                    dialog.dismiss()
                    if (_binding != null) Snackbar.make(binding.root, getString(R.string.afk_pc_added, host.name), Snackbar.LENGTH_SHORT).show()
                    remote.poll()
                } catch (e: Exception) {
                    ok.isEnabled = true
                    ok.setText(R.string.add)
                    val msg = AfkRemote.describe(e)
                    if (e is BadCodeException) codeLayout.error = msg else addrLayout.error = msg
                }
            }
        }
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
        const val LOCAL = "l:"
        const val REMOTE = "r:"
        const val HOST = "h:"
        const val POLL_MS = 3_000L
    }
}
