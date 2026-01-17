package com.cabgon.blackhawk.ui.fragments

import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import androidx.fragment.app.Fragment
import com.cabgon.blackhawk.databinding.FragmentInDevelopmentBinding

class InDevelopmentFragment : Fragment() {

    private var _binding: FragmentInDevelopmentBinding? = null
    private val binding get() = _binding!!

    private var spinAnim: ObjectAnimator? = null
    private var pulseAnim: ObjectAnimator? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentInDevelopmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Rotación suave del ícono
        spinAnim = ObjectAnimator.ofFloat(binding.icon, View.ROTATION, 0f, 360f).apply {
            duration = 2400L
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }

        // Pulso leve del subtítulo (muy sutil)
        pulseAnim = ObjectAnimator.ofFloat(binding.subtitle, View.ALPHA, 0.55f, 1.0f).apply {
            duration = 1200L
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            start()
        }

        binding.title.text = "En Desarrollo"
        binding.subtitle.text = "Esta sección estará disponible en las proximas versiones\nPor ahora, seguimos afinando el módulo."
    }

    override fun onDestroyView() {
        spinAnim?.cancel()
        pulseAnim?.cancel()
        spinAnim = null
        pulseAnim = null
        _binding = null
        super.onDestroyView()
    }
}
