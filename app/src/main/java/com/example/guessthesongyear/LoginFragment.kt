package com.example.guessthesongyear

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.example.guessthesongyear.databinding.FragmentLoginBinding
import com.example.guessthesongyear.util.YouTubeAuthManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException

private const val TAG = "LoginFragment"

class LoginFragment : Fragment() {

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var authManager: YouTubeAuthManager
    
    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d(TAG, "Sign-in result received: ${result.resultCode}")
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            Log.d(TAG, "Sign-in task created, checking for success")
            
            if (task.isSuccessful) {
                Log.d(TAG, "Sign-in successful")
                val account = task.getResult(ApiException::class.java)
                Log.d(TAG, "Signed in as: ${account.email}")
                // Successfully signed in
                navigateToVideoPlayer()
            } else {
                val exception = task.exception
                Log.e(TAG, "Sign-in failed: ${exception?.message}", exception)
                if (exception is ApiException) {
                    val statusCode = exception.statusCode
                    Log.e(TAG, "API Exception status code: $statusCode")
                    
                    if (statusCode == 12501) { // Sign-in canceled by user
                        Toast.makeText(requireContext(), "Sign-in was canceled", Toast.LENGTH_SHORT).show()
                    } else if (statusCode == 10) { // Developer error
                        showDeveloperErrorMessage()
                    } else {
                        Toast.makeText(requireContext(), "Sign-in failed: $statusCode", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(requireContext(), "Sign-in failed", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: ApiException) {
            // Sign in failed
            Log.e(TAG, "Sign-in exception: ${e.message}, status code: ${e.statusCode}", e)
            
            if (e.statusCode == 12501) { // Sign-in canceled by user
                Toast.makeText(requireContext(), "Sign-in was canceled", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Sign-in failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during sign-in: ${e.message}", e)
            Toast.makeText(requireContext(), "Sign-in error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        authManager = YouTubeAuthManager(requireContext())
        
        Log.d(TAG, "Checking if user is already signed in")
        // Check if user is already signed in
        if (authManager.isUserSignedIn()) {
            Log.d(TAG, "User is already signed in, navigating to video player")
            navigateToVideoPlayer()
            return
        } else {
            Log.d(TAG, "User is not signed in, showing sign-in button")
        }
        
        // Add instructions about the "Google hasn't verified this app" screen
        binding.textViewDescription.text = getString(R.string.app_description) + 
            "\n\nNote: During sign-in, you may see a screen saying 'Google hasn't verified this app'. " +
            "This is expected during development. Click 'Continue' to proceed."
        
        // Set up sign-in button
        binding.buttonSignIn.setOnClickListener {
            Log.d(TAG, "Sign-in button clicked, starting sign-in flow")
            signIn()
        }
    }
    
    private fun showDeveloperErrorMessage() {
        val message = "Sign-in failed due to a configuration error. " +
                "Please make sure:\n" +
                "1. You've added your Google account as a test user in Google Cloud Console\n" +
                "2. You've enabled the YouTube Data API\n" +
                "3. Your app's SHA-1 fingerprint is correctly registered"
        
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }
    
    private fun signIn() {
        val signInIntent = authManager.getSignInIntent()
        signInLauncher.launch(signInIntent)
        Log.d(TAG, "Sign-in intent launched")
    }
    
    private fun navigateToVideoPlayer() {
        Log.d(TAG, "Navigating to VideoPlayerFragment")
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, VideoPlayerFragment())
            .commit()
        Log.d(TAG, "Navigation to VideoPlayerFragment committed")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}