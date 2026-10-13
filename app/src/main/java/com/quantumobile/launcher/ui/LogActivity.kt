package com.quantumobile.launcher.ui

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.quantumobile.launcher.QuantumApp
import com.quantumobile.launcher.R
import com.quantumobile.launcher.databinding.ActivityLogBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.RandomAccessFile

class LogActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLogBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        load()
    }

    private fun load() = lifecycleScope.launch {
        val file = (application as QuantumApp).paths.latestLog
        val exitCode = intent.getIntExtra(EXTRA_EXIT_CODE, Int.MIN_VALUE)
        val header = if (exitCode == Int.MIN_VALUE) "" else getString(R.string.game_exited, exitCode) + "\n\n"
        val crashes = header + withContext(Dispatchers.IO) {
            (application as QuantumApp).paths.logs.listFiles { f -> f.name.startsWith("crash-") && f.name.endsWith(".txt") }
                .orEmpty().sortedBy { it.name }
                .joinToString("") { "=== ${it.name} ===\n${it.readText().take(8000)}\n\n" }
        }
        val text = crashes + withContext(Dispatchers.IO) {
            if (!file.isFile) return@withContext if (crashes.isBlank()) getString(R.string.no_log) else ""
            RandomAccessFile(file, "r").use { raf ->
                val start = (raf.length() - MAX_BYTES).coerceAtLeast(0)
                raf.seek(start)
                val bytes = ByteArray((raf.length() - start).toInt())
                raf.readFully(bytes)
                String(bytes)
            }
        }
        binding.log.text = colorize(text)
        binding.scroll.post { binding.scroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.log, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        R.id.refresh -> { load(); true }
        R.id.copy -> {
            getSystemService(android.content.ClipboardManager::class.java)
                .setPrimaryClip(android.content.ClipData.newPlainText("log", binding.log.text.toString().takeLast(100_000)))
            com.google.android.material.snackbar.Snackbar.make(binding.root, R.string.log_copied, com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
            true
        }
        R.id.share -> {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, binding.log.text.toString().takeLast(100_000)), null))
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    /** Highlights errors, warnings and stack traces so crashes stand out. */
    private fun colorize(text: String): CharSequence {
        val out = android.text.SpannableStringBuilder(text)
        val error = getColor(R.color.error)
        val warn = getColor(R.color.warning)
        val dim = getColor(R.color.text_secondary)
        var start = 0
        for (line in text.split('\n')) {
            val color = when {
                line.contains("ERROR") || line.contains("FATAL") || line.contains("Exception") || line.startsWith("Caused by") -> error
                line.trimStart().startsWith("at ") -> error
                line.contains("WARN") -> warn
                line.startsWith("[QML]") -> dim
                else -> null
            }
            if (color != null) {
                out.setSpan(android.text.style.ForegroundColorSpan(color), start, start + line.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            start += line.length + 1
        }
        return out
    }

    companion object {
        private const val MAX_BYTES = 256 * 1024L
        const val EXTRA_EXIT_CODE = "exit_code"
    }
}
