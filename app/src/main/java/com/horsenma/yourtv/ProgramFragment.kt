package com.horsenma.yourtv

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.horsenma.yourtv.data.EPG
import com.horsenma.yourtv.databinding.ProgramBinding
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


class ProgramFragment : Fragment(), ProgramAdapter.ItemListener {
    private var _binding: ProgramBinding? = null
    private val binding get() = _binding!!


    private var programAdapter: ProgramAdapter? = null

    /** 分日视图（G8）：0=今天，1=明天 */
    private var dayIndex = 0

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
        val main = context as MainActivity
        binding.refreshEpg.setOnClickListener { main.getViewModel().updateEPG(force = true) }
        binding.configureEpg.setOnClickListener { main.showSetting() }
        main.getViewModel().epgRefreshStatus.observe(viewLifecycleOwner) {
            if (!isHidden) onVisible()
        }
        binding.program.setOnClickListener {
            hideSelf()
        }

        // 分日切换：OK/点击切换到 今天/明天
        binding.tabToday.setOnClickListener {
            dayIndex = 0
            updateTabs()
            onVisible()
        }
        binding.tabTomorrow.setOnClickListener {
            dayIndex = 1
            updateTabs()
            onVisible()
        }
        binding.tabToday.setOnKeyListener { _, keyCode, event ->
            if (event?.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    binding.tabTomorrow.requestFocus()
                } else {
                    dayIndex = 0
                    updateTabs()
                    onVisible()
                }
                true
            } else {
                false
            }
        }
        binding.tabTomorrow.setOnKeyListener { _, keyCode, event ->
            if (event?.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    binding.tabToday.requestFocus()
                } else {
                    dayIndex = 1
                    updateTabs()
                    onVisible()
                }
                true
            } else {
                false
            }
        }

        onVisible()
        binding.tabToday.post {
            if (_binding != null && !isHidden) binding.tabToday.requestFocus()
        }
    }

    private fun hideSelf() {
        requireActivity().supportFragmentManager.beginTransaction()
            .hide(this)
            .commitAllowingStateLoss()
    }


    fun onVisible() {
        val context = requireActivity()
        val main = context as MainActivity
        val playing = main.playerFragment.tvModel ?: return
        val vm = main.getViewModel()
        val tvModel = vm.listModel.firstOrNull { it.tv.id == playing.tv.id } ?: playing
        binding.programTitle.text = getString(R.string.program_for_channel, tvModel.tv.title)
        val status = vm.epgRefreshStatus.value
        binding.refreshEpg.text = getString(if (status == MainViewModel.EpgRefreshStatus.LOADING)
            R.string.epg_loading else R.string.epg_refresh_now)
        val epgList = tvModel.epgValue
        val now = Utils.getDateTimestamp()
        updateTabs()

        // 分日过滤：今天（节目开始时间在今天）/ 明天
        val todayStr = dayOf(now)
        val tomorrowStr = dayOf(now + 86_400L)
        val dayList = if (dayIndex == 0) {
            epgList.filter { dayOf(it.beginTime.toLong()) == todayStr }
        } else {
            epgList.filter { dayOf(it.beginTime.toLong()) == tomorrowStr }
        }
        // "现在"高亮只在今天视图有效
        val index = if (dayIndex == 0) {
            dayList.indexOfFirst { it.endTime > now }
        } else {
            -1
        }

        // adapter/layoutManager 懒初始化一次，避免每次显示重建导致闪烁/滚动丢失
        if (programAdapter == null) {
            programAdapter = ProgramAdapter(
                context,
                binding.list,
                dayList,
                index,
            )
            binding.list.adapter = programAdapter
            binding.list.layoutManager = LinearLayoutManager(context)
            programAdapter?.setItemListener(this)
        } else {
            programAdapter?.updateData(dayList, index)
        }

        if (dayList.isEmpty()) {
            binding.list.visibility = View.GONE
            binding.empty.visibility = View.VISIBLE
            binding.empty.text = getString(when {
                status == MainViewModel.EpgRefreshStatus.LOADING -> R.string.epg_loading
                !vm.hasEpgSource() -> R.string.epg_not_configured
                status == MainViewModel.EpgRefreshStatus.FAILED -> R.string.epg_load_failed
                epgList.isNotEmpty() || status == MainViewModel.EpgRefreshStatus.SUCCEEDED -> R.string.epg_no_programs_day
                else -> R.string.epg_not_loaded
            })
        } else {
            binding.list.visibility = View.VISIBLE
            binding.empty.visibility = View.GONE
            if (index > -1) {
                programAdapter?.scrollToPositionAndSelect(index)
            }
        }

    }

    private fun updateTabs() {
        if (_binding == null) return
        val context = requireActivity()
        val focusColor = ContextCompat.getColor(context, R.color.focus)
        val blurColor = ContextCompat.getColor(context, R.color.title_blur)
        binding.tabToday.setTextColor(if (dayIndex == 0) focusColor else blurColor)
        binding.tabTomorrow.setTextColor(if (dayIndex == 1) focusColor else blurColor)
    }

    private fun dayOf(epochSeconds: Long): String {
        return SimpleDateFormat("yyyyMMdd", Locale.getDefault())
            .format(Date(epochSeconds * 1000L))
    }

    fun onHidden() {
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            onVisible()
            binding.tabToday.requestFocus()
        } else {
            onHidden()
        }
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        programAdapter = null
        _binding = null
    }

    override fun onItemFocusChange(epg: EPG, hasFocus: Boolean) {
    }

    override fun onKey(keyCode: Int): Boolean {
        return false
    }

    companion object {
        private const val TAG = "ProgramFragment"
    }
}
