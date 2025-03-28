package com.example.guessthesongyear.util

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.guessthesongyear.R
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.services.youtube.YouTubeScopes

private const val TAG = "YouTubeAuthManager"

/**
 * Manages YouTube OAuth authentication
 */
class YouTubeAuthManager(private val context: Context) {

    private lateinit var googleSignInClient: GoogleSignInClient

    init {
        setupGoogleSignIn()
    }

    private fun setupGoogleSignIn() {
        try {
            // Create the Google Sign-In options
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(Scope(YouTubeScopes.YOUTUBE_READONLY))
                // Use a web client ID from your Google Cloud Console
                .requestIdToken(context.getString(R.string.google_oauth_client_id))
                .build()

            googleSignInClient = GoogleSignIn.getClient(context, gso)
            Log.d(TAG, "Google Sign-In client initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up Google Sign-In: ${e.message}", e)
        }
    }

    fun isUserSignedIn(): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        val hasScope = account != null && hasYouTubeScope(account)
        Log.d(TAG, "User is signed in: $hasScope")
        return hasScope
    }

    private fun hasYouTubeScope(account: GoogleSignInAccount): Boolean {
        return GoogleSignIn.hasPermissions(account, Scope(YouTubeScopes.YOUTUBE_READONLY))
    }

    fun getSignInIntent(): Intent {
        Log.d(TAG, "Getting sign-in intent")
        return googleSignInClient.signInIntent
    }

    fun signOut(onComplete: () -> Unit) {
        Log.d(TAG, "Signing out")
        googleSignInClient.signOut().addOnCompleteListener {
            Log.d(TAG, "Sign out completed successfully: ${it.isSuccessful}")
            onComplete()
        }
    }

    companion object {
        const val RC_SIGN_IN = 9001
    }
}