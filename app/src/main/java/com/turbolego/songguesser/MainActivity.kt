package com.turbolego.songguesser

import android.content.DialogInterface
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.appcompat.app.AppCompatActivity
import com.turbolego.songguesser.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var videoPlayerFragment: VideoPlayerFragment? = null
    private var currentDifficulty: Difficulty = Difficulty.MEDIUM

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.appBarLayout) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = systemBars.top)
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = systemBars.bottom)
            insets
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "GuessTheSongYear"

        if (savedInstanceState == null) {
            val fragment = VideoPlayerFragment()
            videoPlayerFragment = fragment
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit()
        } else {
            videoPlayerFragment = supportFragmentManager
                .findFragmentById(R.id.fragment_container) as? VideoPlayerFragment
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)

        // Check current difficulty
        menu.findItem(R.id.action_difficulty_easy)?.isChecked =
            currentDifficulty == Difficulty.EASY
        menu.findItem(R.id.action_difficulty_medium)?.isChecked =
            currentDifficulty == Difficulty.MEDIUM
        menu.findItem(R.id.action_difficulty_hard)?.isChecked =
            currentDifficulty == Difficulty.HARD

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
            R.id.action_reset_score -> {
                confirmResetScore()
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
            .setTitle("GuessTheSongYear")
            .setMessage("En musikk-quiz der du gjetter året til kjente musikkvideoer.\n\n" +
                "Poeng basert på nøyaktighet. Test kunnskapen din!")
            .setPositiveButton("OK", null)
            .show()
    }
}
