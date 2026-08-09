package com.horsenma.yourtv

import android.os.Bundle
import android.os.Handler
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.horsenma.yourtv.data.EPG
import com.horsenma.yourtv.databinding.ProgramBinding


class ProgramFragment : Fragment(), ProgramAdapter.ItemListener {
    private var _binding: ProgramBinding? = null
    private val binding get() = _binding!!

    private val handler = Handler()
    private val delay: Long = 5000

    private lateinit var programAdapter: ProgramAdapter

    private lateinit var viewModel: MainViewModel

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ProgramBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireActivity()
        viewModel = ViewModelProvider(context)[MainViewModel::class.java]

        binding.program.setOnClickListener {
            hideSelf()
        }

        onVisible()
    }

    private fun hideSelf() {
        requireActivity().supportFragmentManager.beginTransaction()
            .hide(this)
            .commitAllowingStateLoss()
    }

    private val hideRunnable = Runnable {
        hideSelf()
    }

    fun onVisible() {
        val context = requireActivity()
        val tvModel = viewModel.groupModel.getCurrent() ?: return
        val epgList = tvModel.epgValue
        val now = Utils.getDateTimestamp()
        val index = epgList.indexOfFirst { it.endTime > now }

        // adapter/layoutManager 懒初始化一次，避免每次显示重建导致闪烁/滚动丢失
        if (!this::programAdapter.isInitialized) {
            programAdapter = ProgramAdapter(
                context,
                binding.list,
                epgList,
                index,
            )
            binding.list.adapter = programAdapter
            binding.list.layoutManager = LinearLayoutManager(context)
            programAdapter.setItemListener(this)
        } else {
            programAdapter.updateData(epgList, index)
        }

        if (epgList.isEmpty()) {
            binding.list.visibility = View.GONE
            binding.empty.visibility = View.VISIBLE
        } else {
            binding.list.visibility = View.VISIBLE
            binding.empty.visibility = View.GONE
            if (index > -1) {
                programAdapter.scrollToPositionAndSelect(index)
            }
        }

        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, delay)
    }

    fun onHidden() {
        handler.removeCallbacks(hideRunnable)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            onVisible()
        } else {
            onHidden()
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(hideRunnable)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onItemFocusChange(epg: EPG, hasFocus: Boolean) {
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, delay)
    }

    override fun onKey(keyCode: Int): Boolean {
        return false
    }

    companion object {
        private const val TAG = "ProgramFragment"
    }
}
