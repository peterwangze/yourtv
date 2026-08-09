package com.horsenma.yourtv

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.marginTop
import androidx.fragment.app.Fragment
import com.horsenma.yourtv.databinding.ErrorBinding

class ErrorFragment : Fragment() {
    private var _binding: ErrorBinding? = null
    private val binding get() = _binding!!
    private var retryListener: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ErrorBinding.inflate(inflater, container, false)

        val application = requireActivity().applicationContext as YourTVApplication

        binding.logo.layoutParams.width = application.px2Px(binding.logo.layoutParams.width)
        binding.logo.layoutParams.height = application.px2Px(binding.logo.layoutParams.height)

        val layoutParams = binding.msg.layoutParams as ViewGroup.MarginLayoutParams
        layoutParams.topMargin = application.px2Px(binding.msg.marginTop)
        binding.msg.layoutParams = layoutParams

        binding.msg.textSize = application.px2PxFont(binding.msg.textSize)

        binding.retryButton.layoutParams.width = application.px2Px(binding.retryButton.layoutParams.width)
        binding.retryButton.textSize = application.px2PxFont(binding.retryButton.textSize)
        binding.hint.textSize = application.px2PxFont(binding.hint.textSize)

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.retryButton.setOnClickListener {
            retryListener?.invoke()
        }
        // OK/确认键在错误页任意位置均可触发重试
        binding.root.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER)
            ) {
                retryListener?.invoke()
                true
            } else {
                false
            }
        }
    }

    fun setMsg(msg: String) {
        if (_binding != null) {
            binding.msg.text = msg
        }
    }

    fun setRetryListener(listener: () -> Unit) {
        retryListener = listener
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "ErrorFragment"
    }
}
