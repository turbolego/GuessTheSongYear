package com.turbolego.songguesser

import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.turbolego.songguesser.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var currentFragment: androidx.fragment.app.Fragment? = null

    private var currentDifficulty: Difficulty = Difficulty.MEDIUM

    val videoPlayerFragment: VideoPlayerFragment?
        get() = currentFragment as? VideoPlayerFragment

    // ── Locale support ──────────────────────────────────────────────────────

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.attachBaseContext(newBase))
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.appBarLayout) { view, insets ->
            view.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top)
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            view.updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom)
            insets
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.app_name)

        if (savedInstanceState == null) {
            navigateToSinglePlayer()
        }
    }

    // ── Menu ────────────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)

        menu.findItem(R.id.action_difficulty_easy)?.isChecked = currentDifficulty == Difficulty.EASY
        menu.findItem(R.id.action_difficulty_medium)?.isChecked = currentDifficulty == Difficulty.MEDIUM
        menu.findItem(R.id.action_difficulty_hard)?.isChecked = currentDifficulty == Difficulty.HARD

        // Check active language
        val currentLang = LocaleHelper.getLanguage(this)
        menu.findItem(R.id.action_language_nb)?.isChecked = currentLang == "nb"
        menu.findItem(R.id.action_language_en)?.isChecked = currentLang == "en"

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_difficulty_easy -> {
                setDifficulty(Difficulty.EASY, item)
                true
            }
            R.id.action_difficulty_medium -> {
                setDifficulty(Difficulty.MEDIUM, item)
                true
            }
            R.id.action_difficulty_hard -> {
                setDifficulty(Difficulty.HARD, item)
                true
            }
            R.id.action_language_nb -> {
                LocaleHelper.setLanguage(this, "nb")
                true
            }
            R.id.action_language_en -> {
                LocaleHelper.setLanguage(this, "en")
                true
            }
            R.id.action_local_multiplayer -> {
                navigateToLocalMultiplayer()
                true
            }
            R.id.action_host_game -> {
                navigateToHostGame()
                true
            }
            R.id.action_join_game -> {
                navigateToJoinGame()
                true
            }
            R.id.action_reset_score -> {
                confirmResetScore()
                true
            }
            R.id.action_clear_duplicates -> {
                val count = videoPlayerFragment?.getDuplicateCount() ?: 0
                AlertDialog.Builder(this)
                    .setTitle(R.string.clear_duplicates_title)
                    .setMessage(getString(R.string.clear_duplicates_message, count))
                    .setPositiveButton(R.string.reset) { _, _ ->
                        videoPlayerFragment?.resetDuplicateTracker()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                true
            }
            R.id.action_settings -> {
                navigateToSettings()
                true
            }
            R.id.action_debug_youtube -> {
                navigateToDebugYouTube()
                true
            }
            R.id.action_debug_wifi -> {
                navigateToDebugWifi()
                true
            }
            R.id.action_about -> {
                showAbout()
                true
            }
            R.id.action_debug -> {
                navigateToDebug()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setDifficulty(difficulty: Difficulty, menuItem: MenuItem) {
        currentDifficulty = difficulty
        menuItem.isChecked = true
        videoPlayerFragment?.setDifficulty(difficulty)
        binding.toolbar.subtitle = getString(R.string.difficulty_label, getString(difficulty.labelResId))
    }

    // ── Navigation ──────────────────────────────────────────────────────────

    private fun navigateToSinglePlayer() {
        val frag = VideoPlayerFragment()
        frag.setDifficulty(currentDifficulty)
        currentFragment = frag
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, frag)
            .commit()
        supportActionBar?.title = getString(R.string.app_name)
    }

    private fun navigateToLocalMultiplayer() {
        val frag = MultiplayerSetupFragment()
        currentFragment = frag
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, frag)
            .addToBackStack("multiplayer_setup")
            .commit()
        supportActionBar?.title = getString(R.string.multiplayer_setup_title)
    }

    fun startMultiplayerGame(players: List<MultiPlayerManager.Player>) {
        val names = players.map { it.name }
        val frag = VideoPlayerFragment.newInstance(names)
        frag.setDifficulty(currentDifficulty)
        currentFragment = frag
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, frag)
            .addToBackStack("multiplayer_game")
            .commit()
        supportActionBar?.title = getString(R.string.multiplayer_setup_title)
    }

    private fun navigateToHostGame() {
        val frag = HostGameFragment()
        currentFragment = frag
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, frag)
            .addToBackStack("host_game")
            .commit()
        supportActionBar?.title = getString(R.string.host_game_title)
    }

    private fun navigateToJoinGame() {
        val frag = JoinGameFragment()
        currentFragment = frag
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, frag)
            .addToBackStack("join_game")
            .commit()
        supportActionBar?.title = getString(R.string.join_game_title)
    }

    private fun navigateToDebug() {
        val frag = DebugFragment()
        currentFragment = frag
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, frag)
            .addToBackStack("debug")
            .commit()
        supportActionBar?.title = getString(R.string.menu_debug)
    }

    private fun navigateToSettings() {
        val frag = SettingsFragment()
        currentFragment = frag
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, frag)
            .addToBackStack("settings")
            .commit()
        supportActionBar?.title = getString(R.string.menu_settings)
    }

    private fun navigateToDebugYouTube() {
        val frag = DebugYouTubeFragment()
        currentFragment = frag
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, frag)
            .addToBackStack("debug_youtube")
            .commit()
        supportActionBar?.title = "Debug: YouTube"
    }

    private fun navigateToDebugWifi() {
        val frag = DebugWifiFragment()
        currentFragment = frag
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, frag)
            .addToBackStack("debug_wifi")
            .commit()
        supportActionBar?.title = "Debug: WiFi"
    }

    private fun confirmResetScore() {
        val stats = videoPlayerFragment?.getStats() ?: ""
        AlertDialog.Builder(this)
            .setTitle(R.string.reset_score_title)
            .setMessage(getString(R.string.reset_score_message, stats))
            .setPositiveButton(R.string.reset) { _: DialogInterface, _: Int ->
                videoPlayerFragment?.resetScore()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // Forward QR scan result to JoinGameFragment if active
        val joinFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
        if (joinFragment is JoinGameFragment) {
            joinFragment.onQrScanResult(requestCode, resultCode, data)
        }
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle(R.string.about_title)
            .setMessage(R.string.about_message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

}