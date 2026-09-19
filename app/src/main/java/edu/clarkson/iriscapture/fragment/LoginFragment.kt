package edu.clarkson.iriscapture.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import edu.clarkson.iriscapture.MainViewModel
import edu.clarkson.iriscapture.R
import edu.clarkson.iriscapture.databinding.FragmentLoginBinding

class LoginFragment : Fragment() {

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

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

        binding.btnEnter.setOnClickListener {
            // RESTORED 2026-09-19. HEAD required a 6-digit ID while the layout caps the
            // field at maxLength=3, so ENTER could never succeed and the app could not get
            // past this screen. The pre-loss build required 3 digits and also read and
            // validated the images-per-eye field, which HEAD ignored entirely.
            val id = binding.etParticipantId.text.toString()
            val imagesPerEye = binding.etImagesPerEye.text.toString().toIntOrNull()

            if (id.length != 3) {
                Toast.makeText(requireContext(), "Please enter a 3-digit ID", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (imagesPerEye == null || imagesPerEye < 1 || imagesPerEye > 20) {
                Toast.makeText(requireContext(), "Images per eye must be between 1 and 20", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            viewModel.setParticipantId(id)
            viewModel.setImagesPerEye(imagesPerEye)
            findNavController().navigate(R.id.action_login_to_permissions)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
