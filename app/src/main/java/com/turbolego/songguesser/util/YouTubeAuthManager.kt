package com.turbolego.songguesser.util

import android.content.Context
import android.content.Intent
import android.util.Log
import com.turbolego.songguesser.R
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
        // Use the modern AndroidX Credentials API to initialize the client
        googleSignInClient = com.google.android.gms.auth.api.signin.GoogleSignIn.getClient(context, GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(YouTubeScopes.YOUTUBE_READONLY))
            .requestIdToken(context.getString(R.string.google_oauth_client_id))
            .build())
        Log.d(TAG, "Google Sign-In client initialized successfully")
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
}