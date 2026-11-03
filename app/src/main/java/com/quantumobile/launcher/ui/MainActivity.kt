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
        binding.toolbar.inflateMenu(R.menu.main_toolbar)
        binding.toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_accounts -> { navigateTo(R.id.nav_accounts); true }
                R.id.action_settings -> { navigateTo(R.id.nav_settings); true }
                else -> false
            }
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            binding.toolbar.navigationIcon = null // leaving Accounts/Settings by tapping a tab
            show(
                when (item.itemId) {
                    R.id.nav_instances -> InstancesFragment()
                    R.id.nav_mods -> ModsFragment()
                    R.id.nav_visual -> VisualFragment()
                    R.id.nav_afk -> AfkFragment()
                    else -> HomeFragment()
                },
                item.title,
            )
            true
        }
        if (savedInstanceState == null) binding.bottomNav.selectedItemId = intent.getIntExtra(EXTRA_TAB, R.id.nav_home)
        askNotificationPermission()
        val app = application as com.quantumobile.launcher.QuantumApp
        if (savedInstanceState == null) app.prefetchComponents()
        app.startAfk() // servers left on keep (or start again) idling after the app or phone restarted
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.hasExtra(EXTRA_TAB)) navigateTo(intent.getIntExtra(EXTRA_TAB, R.id.nav_home))
    }

    override fun onResume() {
        super.onResume()
        checkAbnormalGameExit()
    }

    /** The game process leaves a marker while it runs; finding it with no game process alive means it died. */
    private fun checkAbnormalGameExit() {
        val app = application as com.quantumobile.launcher.QuantumApp
        val marker = com.quantumobile.launcher.game.JvmStarter.runningMarker(app.paths.latestLog.path)
        if (!marker.isFile) return
        val am = getSystemService(android.app.ActivityManager::class.java)
        val alive = am.runningAppProcesses.orEmpty().any { it.processName.endsWith(":game") }
        if (alive) return
        marker.delete()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.game_crashed_title)
            .setMessage(R.string.game_crashed_message)
            .setPositiveButton(R.string.game_log) { _, _ -> startActivity(android.content.Intent(this, LogActivity::class.java)) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private val notificationPermission =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }

    /** Progress and "ready to play" notifications need this on Android 13+; asked once. */
    private fun askNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33 || com.quantumobile.launcher.service.Notifications.canNotify(this)) return
        val prefs = getSharedPreferences("launcher", MODE_PRIVATE)
        if (prefs.getBoolean("asked_notifications", false)) return
        prefs.edit().putBoolean("asked_notifications", true).apply()
        notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Switches tabs from inside a fragment (e.g. the setup checklist on Home). */
    fun navigateTo(itemId: Int) {
        when (itemId) {
            R.id.nav_accounts -> return showOffBar(AccountsFragment(), getString(R.string.nav_accounts))
            R.id.nav_settings -> return showOffBar(SettingsFragment(), getString(R.string.nav_settings))
        }
        binding.toolbar.navigationIcon = null
        binding.bottomNav.selectedItemId = itemId
    }

    /** Accounts and Settings are not bottom tabs (the bar holds five); they open from the toolbar and from Home. */
    private fun showOffBar(fragment: Fragment, title: String) {
        binding.bottomNav.menu.setGroupCheckable(0, true, false) // no tab highlighted while it is open
        for (i in 0 until binding.bottomNav.menu.size()) binding.bottomNav.menu.getItem(i).isChecked = false
        binding.bottomNav.menu.setGroupCheckable(0, true, true)
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { navigateTo(R.id.nav_home) }
        show(fragment, title)
    }

    companion object {
        /** Bottom tab to open (e.g. [R.id.nav_afk] from the AFK notification). */
        const val EXTRA_TAB = "tab"
    }

    private fun show(fragment: Fragment, title: CharSequence?) {
        supportActionBar?.title = title
        supportFragmentManager.commit {
            setTransition(androidx.fragment.app.FragmentTransaction.TRANSIT_FRAGMENT_FADE)
            replace(R.id.content, fragment)
        }
    }
}
