package com.quantumobile.launcher.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.quantumobile.launcher.R
import com.quantumobile.launcher.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        binding.bottomNav.setOnItemSelectedListener { item ->
            show(
                when (item.itemId) {
                    R.id.nav_accounts -> AccountsFragment()
                    R.id.nav_instances -> InstancesFragment()
                    R.id.nav_settings -> SettingsFragment()
                    else -> HomeFragment()
                },
                item.title,
            )
            true
        }
        if (savedInstanceState == null) binding.bottomNav.selectedItemId = R.id.nav_home
    }

    /** Switches tabs from inside a fragment (e.g. the setup checklist on Home). */
    fun navigateTo(itemId: Int) {
        binding.bottomNav.selectedItemId = itemId
    }

    private fun show(fragment: Fragment, title: CharSequence?) {
        supportActionBar?.title = title
        supportFragmentManager.commit { replace(R.id.content, fragment) }
    }
}
