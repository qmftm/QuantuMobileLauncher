package com.quantumobile.launcher.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.quantumobile.launcher.R
import com.quantumobile.launcher.databinding.FragmentDownloadBinding

/**
 * The "Download" tab: mods on one side, resource packs and shaders (the former Visual tab) on the other,
 * switched with a segmented control. Each section is the existing fragment, hosted as a child.
 */
class DownloadFragment : Fragment() {
    private var _binding: FragmentDownloadBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDownloadBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val restored = savedInstanceState?.getBoolean(KEY_VISUAL) ?: false
        binding.sections.check(if (restored) R.id.btn_section_visual else R.id.btn_section_mods)
        binding.sections.addOnButtonCheckedListener { _, id, checked ->
            if (checked) show(id == R.id.btn_section_visual)
        }
        // After a rotation/recreate the child fragment is restored by the fragment manager already.
        if (savedInstanceState == null) show(false)
    }

    private fun show(visual: Boolean) {
        val wanted = if (visual) TAG_VISUAL else TAG_MODS
        if (childFragmentManager.findFragmentByTag(wanted)?.isVisible == true) return
        childFragmentManager.commit {
            setReorderingAllowed(true)
            replace(R.id.download_container, if (visual) VisualFragment() else ModsFragment(), wanted)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_VISUAL, _binding?.sections?.checkedButtonId == R.id.btn_section_visual)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val KEY_VISUAL = "visual"
        const val TAG_MODS = "download_mods"
        const val TAG_VISUAL = "download_visual"
    }
}
