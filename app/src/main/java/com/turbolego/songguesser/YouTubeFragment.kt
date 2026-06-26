package com.turbolego.songguesser

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.turbolego.songguesser.databinding.FragmentYoutubeBinding
import kotlinx.coroutines.launch

/**
 * Fragment for displaying YouTube search results
 */
class YouTubeFragment : Fragment() {
    private var _binding: FragmentYoutubeBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var viewModel: YouTubeViewModel
    private lateinit var adapter: YouTubeAdapter
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentYoutubeBinding.inflate(inflater, container, false)
        
        viewModel = ViewModelProvider(requireActivity())[YouTubeViewModel::class.java]
        adapter = YouTubeAdapter { videoId ->
            // Navigate to video player with video ID
            val fragment = VideoPlayerFragment()
            fragment.arguments = Bundle().apply {
                putString("videoId", videoId)
            }
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .addToBackStack(null)
                .commit()
        }
        
        binding.youtubeRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            this.adapter = adapter
            adapter?.let { it.setHasStableIds(true) }
            addItemDecoration(
                androidx.recyclerview.widget.DividerItemDecoration(
                    requireContext(),
                    androidx.recyclerview.widget.DividerItemDecoration.VERTICAL
                )
            )
        }
        
        binding.youtubeSearchButton.setOnClickListener {
            val query = binding.youtubeSearchInput.text.toString().trim()
            if (query.isNotEmpty()) {
                viewModel.searchVideos(query)
            }
        }
        
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Collect ViewModel state using StateFlow
        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                adapter.submitList(state.videos)
                
                binding.youtubeRecyclerView.visibility = if (state.videos.isEmpty() && state.isLoading) {
                    View.GONE
                } else {
                    View.VISIBLE
                }
                
                state.error?.let { error ->
                    Toast.makeText(
                        requireContext(),
                        error,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
