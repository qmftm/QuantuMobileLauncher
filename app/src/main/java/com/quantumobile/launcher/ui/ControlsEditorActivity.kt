package com.quantumobile.launcher.ui

import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.quantumobile.launcher.R
import com.quantumobile.launcher.databinding.ActivityControlsEditorBinding
import com.quantumobile.launcher.game.ControlLayoutStore
import com.quantumobile.launcher.game.ControlSpec
import com.quantumobile.launcher.game.KeyCatalog

/** Lets the user arrange the on-screen game buttons without starting Minecraft. Changes save as soon as they are made. */
class ControlsEditorActivity : AppCompatActivity() {
    private lateinit var binding: ActivityControlsEditorBinding
    private lateinit var store: ControlLayoutStore
    private var buttons: List<ControlSpec> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityControlsEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())

        store = ControlLayoutStore.forContext(this)
        buttons = store.load()
        binding.editor.buttons = buttons
        binding.editor.onChanged = { update(it) }
        binding.editor.onTap = ::edit
        binding.add.setOnClickListener { addButton() }
        binding.done.setOnClickListener { finish() }
        binding.reset.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.controls_reset_confirm)
                .setPositiveButton(R.string.controls_reset) { _, _ ->
                    store.reset()
                    buttons = store.load()
                    binding.editor.selectedId = null
                    binding.editor.buttons = buttons
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        binding.hint.isVisible = true
        binding.hint.postDelayed({ binding.hint.isVisible = false }, 4000)
    }

    private fun update(list: List<ControlSpec>) {
        buttons = list
        binding.editor.buttons = list
        store.save(list)
    }

    private fun addButton() {
        var n = 1
        while (buttons.any { it.id == "custom$n" }) n++
        val choice = KeyCatalog.choices.first { it.name == "F" }
        val spec = ControlSpec("custom$n", choice.name, choice.kind, choice.code, x = 120f, y = 100f)
        update(buttons + spec)
        binding.editor.selectedId = spec.id
        edit(spec)
    }

    private fun edit(original: ControlSpec) {
        var spec = original
        val dp = resources.displayMetrics.density
        fun pad(v: Int) = (v * dp).toInt()

        val label = EditText(this).apply {
            setText(spec.label)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            hint = getString(R.string.controls_label)
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
        }
        val action = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = KeyCatalog.find(spec.kind, spec.code)?.name ?: spec.label
            setOnClickListener {
                val names = KeyCatalog.choices.map { it.name }.toTypedArray()
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.controls_action)
                    .setItems(names) { _, i ->
                        val c = KeyCatalog.choices[i]
                        val wasDefaultLabel = label.text.toString() == (KeyCatalog.find(spec.kind, spec.code)?.name ?: "") || label.text.isEmpty()
                        spec = spec.copy(kind = c.kind, code = c.code)
                        text = c.name
                        if (wasDefaultLabel) label.setText(c.name.take(6))
                    }
                    .show()
            }
        }
        val sizeText = TextView(this).apply { text = getString(R.string.controls_size) + ": ${spec.size.toInt()} dp" }
        val size = Slider(this).apply {
            valueFrom = 32f; valueTo = 120f; stepSize = 4f
            value = spec.size.coerceIn(32f, 120f).let { Math.round(it / 4f) * 4f }
            addOnChangeListener { _, v, _ ->
                spec = spec.copy(size = v)
                sizeText.text = getString(R.string.controls_size) + ": ${v.toInt()} dp"
            }
        }
        val toggle = CheckBox(this).apply { text = getString(R.string.controls_toggle); isChecked = spec.toggle }
        val repeat = CheckBox(this).apply { text = getString(R.string.controls_repeat); isChecked = spec.repeat }
        val menus = CheckBox(this).apply { text = getString(R.string.controls_in_menus); isChecked = spec.showInMenus }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(24), pad(8), pad(24), 0)
            val lp = ViewGroup.LayoutParams.MATCH_PARENT
            addView(label, LinearLayout.LayoutParams(lp, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(action, LinearLayout.LayoutParams(lp, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(sizeText)
            addView(size, LinearLayout.LayoutParams(lp, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(toggle)
            addView(repeat)
            addView(menus)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(content) }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.controls_edit_button)
            .setView(scroll)
            .setPositiveButton(R.string.controls_save) { _, _ ->
                val text = label.text.toString().ifBlank { spec.label }
                val saved = spec.copy(label = text, toggle = toggle.isChecked, repeat = repeat.isChecked, showInMenus = menus.isChecked)
                update(buttons.map { if (it.id == saved.id) saved else it })
            }
            .setNeutralButton(R.string.controls_delete) { _, _ ->
                update(buttons.filterNot { it.id == original.id })
                binding.editor.selectedId = null
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
