package com.turbolego.songguesser

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.turbolego.songguesser.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        // Only add the initial fragment if this is the first creation
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, VideoPlayerFragment())
                .commit()
        }

        // Set up YouTube button click listener
        binding.btnYoutube.setOnClickListener {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, YouTubeFragment())
                .commit()
        }
    }
}
