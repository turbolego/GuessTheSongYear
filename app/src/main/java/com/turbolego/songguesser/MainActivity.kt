package com.turbolego.songguesser

import android.content.DialogInterface
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.appcompat.app.AppCompatActivity
import com.turbolego.songguesser.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var currentFragment: androidx.fragment.app.Fragment? = null

    private var currentDifficulty: Difficulty = Difficulty.MEDIUM

    companion object {
        @Volatile var activeFragment: VideoPlayerFragment? = null
    }

    val videoPlayerFragment: VideoPlayerFragment?
        get() = currentFragment as? VideoPlayerFragment

    override fun onCreate(savedInstanceState: Bundle?) {
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
        supportActionBar?.title = "GuessTheSongYear"

        if (savedInstanceState == null) {
            navigateToSinglePlayer()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)

        menu.findItem(R.id.action_difficulty_easy)?.isChecked = currentDifficulty == Difficulty.EASY
        menu.findItem(R.id.action_difficulty_medium)?.isChecked = currentDifficulty == Difficulty.MEDIUM
        menu.findItem(R.id.action_difficulty_hard)?.isChecked = currentDifficulty == Difficulty.HARD

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
                    .setTitle("Fjern duplikat-sporing?")
                    .setMessage("$count duplikater hoppet over denne økten.\nNullstill telleren.")
                    .setPositiveButton("Nullstill") { _, _ ->
                        videoPlayerFragment?.resetDuplicateTracker()
                    }
                    .setNegativeButton("Avbryt", null)
                    .show()
                true
            }
            R.id.action_about -> {
                showAbout()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setDifficulty(difficulty: Difficulty, menuItem: MenuItem) {
        currentDifficulty = difficulty
        menuItem.isChecked = true
        videoPlayerFragment?.setDifficulty(difficulty)
        binding.toolbar.subtitle = "Vanskelighet: ${difficulty.label}"
    }

    // ── Navigation ──────────────────────────────────────────────────────────

    private fun navigateToSinglePlayer() {
        val frag = VideoPlayerFragment()
        frag.setDifficulty(currentDifficulty)
        currentFragment = frag
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, frag)
            .commit()
        supportActionBar?.title = "GuessTheSongYear"
    }

    private fun navigateToLocalMultiplayer() {
        val frag = MultiplayerSetupFragment()
        currentFragment = frag
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, frag)
            .addToBackStack("multiplayer_setup")
            .commit()
        supportActionBar?.title = "Flerspiller"
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
        supportActionBar?.title = "Flerspiller"
    }

    private fun navigateToHostGame() {
        val frag = HostGameFragment()
        currentFragment = frag
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, frag)
            .addToBackStack("host_game")
            .commit()
        supportActionBar?.title = "Host spill"
    }

    private fun navigateToJoinGame() {
        val frag = JoinGameFragment()
        currentFragment = frag
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, frag)
            .addToBackStack("join_game")
            .commit()
        supportActionBar?.title = "Bli med i spill"
    }

    private fun confirmResetScore() {
        val stats = videoPlayerFragment?.getStats() ?: ""
        AlertDialog.Builder(this)
            .setTitle("Nullstill poeng?")
            .setMessage("Dette sletter all statistikk:\n\n$stats")
            .setPositiveButton("Nullstill") { _: DialogInterface, _: Int ->
                videoPlayerFragment?.resetScore()
            }
            .setNegativeButton("Avbryt", null)
            .show()
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle("PlaylistThe")
            .setMessage("En musikk-quiz der du gjetter begjæret til sanger. Multiplayer for flerparty!")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showFragment(fragment: androidx.fragment.app.Fragment) {
        currentFragment = fragment
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }
}