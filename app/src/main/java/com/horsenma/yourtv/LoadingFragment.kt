package com.horsenma.yourtv

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.horsenma.yourtv.databinding.LoadingBinding

class LoadingFragment : Fragment() {
    private var _binding: LoadingBinding? = null
    private val binding get() = _binding!!
    private val TAG = "LoadingFragment"

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LoadingBinding.inflate(inflater, container, false)

        val application = requireActivity().applicationContext as? YourTVApplication
        if (application != null) {
            binding.bar.layoutParams.width = application.px2Px(binding.bar.layoutParams.width)
            binding.bar.layoutParams.height = application.px2Px(binding.bar.layoutParams.height)
        } else {
            binding.bar.layoutParams.width = (100 * resources.displayMetrics.density).toInt()
            binding.bar.layoutParams.height = (20 * resources.displayMetrics.density).toInt()
        }

        if (application != null) {
            binding.logo.layoutParams.width = application.px2Px(binding.logo.layoutParams.width)
            binding.logo.layoutParams.height = application.px2Px(binding.logo.layoutParams.height)
            binding.message.textSize = application.px2PxFont(binding.message.textSize)
        }

        binding.bar.visibility = View.VISIBLE
        (requireActivity() as MainActivity).ready()
        return binding.root
    }

    private fun stopMusicAndSwitchToLive() {
        val activity = activity as? MainActivity
        activity?.let {
            val channelFragment = it.supportFragmentManager.findFragmentByTag("ChannelFragmentTag") as? ChannelFragment
            channelFragment?.playNow()
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            stopMusicAndSwitchToLive()
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
        stopMusicAndSwitchToLive()
    }

    companion object {
        const val TAG = "LoadingFragment"
    }
}
