package com.quantumobile.launcher.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.quantumobile.launcher.QuantumApp
import com.quantumobile.launcher.R
import com.quantumobile.launcher.core.afk.AfkLogKind
import com.quantumobile.launcher.core.afk.AfkLogLine
import com.quantumobile.launcher.core.afk.remote.Ops
import com.quantumobile.launcher.core.afk.remote.RemoteRequest
import com.quantumobile.launcher.databinding.ActivityAfkLogBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One AFK server's log: connection events and the chat the bot sees. Live while open; works for PC servers too. */
class AfkLogActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAfkLogBinding
    private val app get() = application as QuantumApp
    private val lines = mutableListOf<AfkLogLine>()
    private var chatOnly = false
    private val adapter = LineAdapter()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val serverId get() = intent.getStringExtra(EXTRA_SERVER)!!
    private val hostId get() = intent.getStringExtra(EXTRA_HOST)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAfkLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = intent.getStringExtra(EXTRA_TITLE)
        binding.list.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.list.adapter = adapter

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val host = hostId
                if (host == null) {
                    binding.status.text = getString(R.string.afk_log_phone)
                    app.afk.logVersion.collect { show(app.afk.logs(serverId)) }
                } else {
                    binding.status.text = getString(R.string.afk_log_pc, app.afkRemote.host(host)?.name.orEmpty())
                    pollRemote(host)
                }
            }
        }
    }

    /** PC servers: fetch new lines every couple of seconds. */
    private suspend fun pollRemote(hostId: String) {
        var since = lines.lastOrNull()?.time ?: 0
        while (true) {
            try {
                val new = app.afkRemote.call(hostId, RemoteRequest(Ops.LOGS, 0, id = serverId, since = since)).logs
                binding.status.text = getString(R.string.afk_log_pc, app.afkRemote.host(hostId)?.name.orEmpty())
                if (new.isNotEmpty()) {
                    since = new.last().time
                    show(lines + new)
                } else if (lines.isEmpty()) {
                    show(emptyList())
                }
            } catch (e: Exception) {
                binding.status.text = getString(R.string.afk_pc_offline, AfkRemote.describe(e))
            }
            delay(2_000)
        }
    }

    private fun show(all: List<AfkLogLine>) {
        val atBottom = !binding.list.canScrollVertically(1)
        if (all !== lines) { lines.clear(); lines.addAll(all.takeLast(1000)) }
        adapter.items = lines.filter { !chatOnly || it.kind == AfkLogKind.CHAT }
        binding.empty.isVisible = adapter.items.isEmpty()
        if (atBottom && adapter.itemCount > 0) binding.list.scrollToPosition(adapter.itemCount - 1)
    }

    private fun format(line: AfkLogLine): CharSequence {
        val sb = SpannableStringBuilder(timeFormat.format(Date(line.time)) + "  ")
        sb.setSpan(ForegroundColorSpan(getColor(R.color.text_secondary)), 0, sb.length, 0)
        val start = sb.length
        sb.append(line.text)
        val color = when (line.kind) {
            AfkLogKind.CHAT -> R.color.text_primary
            AfkLogKind.INFO -> R.color.brand
            AfkLogKind.WARN -> R.color.warning
            AfkLogKind.ERROR -> R.color.error
        }
        sb.setSpan(ForegroundColorSpan(getColor(color)), start, sb.length, 0)
        return sb
    }

    private fun plainText() = adapter.items.joinToString("\n") { "${timeFormat.format(Date(it.time))}  ${it.text}" }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.afk_log, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        R.id.chat_only -> {
            chatOnly = !chatOnly
            item.isChecked = chatOnly
            show(lines)
            true
        }
        R.id.copy -> {
            getSystemService(android.content.ClipboardManager::class.java)
                .setPrimaryClip(android.content.ClipData.newPlainText("log", plainText()))
            Snackbar.make(binding.root, R.string.log_copied, Snackbar.LENGTH_SHORT).show()
            true
        }
        R.id.share -> {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, plainText()), null))
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private inner class LineAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var items: List<AfkLogLine> = emptyList()
            set(value) {
                field = value
                @Suppress("NotifyDataSetChanged")
                notifyDataSetChanged()
            }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val tv = TextView(parent.context).apply {
                textSize = 13f
                setTextIsSelectable(true)
                val pad = (3 * resources.displayMetrics.density).toInt()
                setPadding(0, pad, 0, pad)
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            return object : RecyclerView.ViewHolder(tv) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            (holder.itemView as TextView).text = format(items[position])
        }
    }

    companion object {
        private const val EXTRA_SERVER = "server"
        private const val EXTRA_HOST = "host"
        private const val EXTRA_TITLE = "title"

        fun intent(context: Context, serverId: String, hostId: String?, title: String) =
            Intent(context, AfkLogActivity::class.java)
                .putExtra(EXTRA_SERVER, serverId).putExtra(EXTRA_HOST, hostId).putExtra(EXTRA_TITLE, title)
    }
}
