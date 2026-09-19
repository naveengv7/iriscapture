package edu.clarkson.iriscapture.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import edu.clarkson.iriscapture.MainViewModel
import edu.clarkson.iriscapture.R
import edu.clarkson.iriscapture.databinding.FragmentModeSelectionBinding

class ModeSelectionFragment : Fragment() {

    companion object {
        // Capture mode constants
        const val MODE_TELEPHOTO = "telephoto"      // 5x optical, manual alignment
        const val MODE_MAIN_8X = "main_8x"          // Main camera, 8x digital, MediaPipe
        const val MODE_FRONT = "front"              // Front camera, max zoom, MediaPipe
    }

    private var _binding: FragmentModeSelectionBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentModeSelectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Display participant ID
        binding.tvParticipant.text = "Participant: ${viewModel.participantId}"

        // Mode 1: Telephoto (5x optical, manual alignment)
        binding.cardTelephoto.setOnClickListener {
            navigateToCamera(MODE_TELEPHOTO)
        }

        // Mode 2: Main Camera 8x (MediaPipe detection)
        binding.cardMain8x.setOnClickListener {
            navigateToCamera(MODE_MAIN_8X)
        }

        // Mode 3: Front Camera (Self-capture with MediaPipe)
        binding.cardFront.setOnClickListener {
            navigateToCamera(MODE_FRONT)
        }

        // Logout / Change Participant
        binding.btnLogout.setOnClickListener {
            findNavController().navigate(R.id.action_mode_selection_to_login)
        }
    }

    private fun navigateToCamera(mode: String) {
        val bundle = Bundle().apply {
            putString("capture_mode", mode)
        }
        findNavController().navigate(R.id.action_mode_selection_to_camera, bundle)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
