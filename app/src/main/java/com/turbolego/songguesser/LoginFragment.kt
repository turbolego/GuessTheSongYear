package com.turbolego.songguesser

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.turbolego.songguesser.databinding.FragmentLoginBinding

class LoginFragment : Fragment() {

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.buttonSignIn.setOnClickListener {
            // Simulate authentication - in real app, this would call YouTube API
            authenticate()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun authenticate() {
        // Simulate authentication delay
        view?.postDelayed({
            // For now, just navigate to VideoPlayerFragment
            // In a real app, you would:
            // 1. Get OAuth token from user
            // 2. Verify with YouTube API
            // 3. Navigate to VideoPlayerFragment with user data
            
            val transaction = childFragmentManager.beginTransaction()
            transaction.replace(R.id.fragment_container, VideoPlayerFragment())
            transaction.commit()
        }, 1000)
    }
}