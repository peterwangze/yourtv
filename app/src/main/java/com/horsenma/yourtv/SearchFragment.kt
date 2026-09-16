package com.horsenma.yourtv

import android.os.Bundle
import android.content.Context
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.EditorInfo
import androidx.core.widget.doAfterTextChanged
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.horsenma.yourtv.databinding.SearchBinding
import com.horsenma.yourtv.models.TVListModel
import com.horsenma.yourtv.models.TVModel

/**
 * 频道搜索 + 最近观看 浮层（对齐电视家"搜索频道"S40 / OTT Navigator"搜索过滤"、
 * 电视家"历史记录"S41 / OTT Navigator"继续观看"）：
 * - 输入为空：展示「最近观看」（最近播放的频道，按时间倒序）
 * - 输入框按 OK 打开输入法，也支持遥控器字母/数字键，实时过滤频道
 * - 结果按 OK 播放；返回键或 MENU 关闭搜索
 */
class SearchFragment : Fragment() {
    private var _binding: SearchBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: MainViewModel
    private var adapter: TVListAdapter? = null
    private var listModel = TVListModel("search", 0)
    private var shown = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = SearchBinding.inflate(inflater, container, false)
        val application = requireActivity().applicationContext as? YourTVApplication
        application?.let { app ->
            binding.title.textSize = app.px2PxFont(binding.title.textSize)
            binding.query.textSize = app.px2PxFont(binding.query.textSize)
            binding.hint.textSize = app.px2PxFont(binding.hint.textSize)
            binding.sectionTitle.textSize = app.px2PxFont(binding.sectionTitle.textSize)
            binding.empty.textSize = app.px2PxFont(binding.empty.textSize)
        }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity())[MainViewModel::class.java]

        binding.list.layoutManager = LinearLayoutManager(requireContext())
        adapter = TVListAdapter(requireContext(), binding.list, object : TVListAdapter.ItemListener {
            override fun onItemFocusChange(tvModel: TVModel, hasFocus: Boolean) {
            }

            override fun onItemClicked(position: Int, type: String) {
                listModel.getTVModel(position)?.let { tvModel ->
                    Log.d(TAG, "SearchFragment play: ${tvModel.tv.title}")
                    tvModel.setReady()
                    viewModel.setCurrentTvModel(tvModel)
                    viewModel.triggerPlay(tvModel)
                }
                hideSelf()
            }

            override fun onKey(listAdapter: TVListAdapter, keyCode: Int): Boolean {
                return handleSearchKey(keyCode)
            }
        })
        binding.list.adapter = adapter
        binding.query.doAfterTextChanged { refresh() }
        binding.query.setOnClickListener { showKeyboard() }
        binding.query.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                hideKeyboard()
                focusResults()
                true
            } else if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                showKeyboard()
                true
            } else false
        }
        binding.query.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard()
                focusResults()
                true
            } else false
        }

        binding.root.setOnKeyListener { _, keyCode, event ->
            if (event?.action == KeyEvent.ACTION_DOWN) {
                handleSearchKey(keyCode)
            } else {
                false
            }
        }
        refresh()
    }

    /** 每次显示时刷新数据（重新解析最近观看/结果） */
    fun onShow() {
        if (_binding == null) return
        shown = true
        refresh()
        binding.query.requestFocus()
    }

    fun handleSearchKey(keyCode: Int): Boolean {
        if (_binding == null) return false
        if (binding.query.hasFocus() && keyCode != KeyEvent.KEYCODE_BACK &&
            keyCode != KeyEvent.KEYCODE_ESCAPE && keyCode != KeyEvent.KEYCODE_MENU &&
            keyCode != KeyEvent.KEYCODE_SETTINGS) return false
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                hideSelf()
                true
            }
            KeyEvent.KEYCODE_DEL -> {
                binding.query.text?.let { if (it.isNotEmpty()) it.delete(it.length - 1, it.length) }
                true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> {
                hideSelf()
                true
            }
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                binding.query.append(('0' + (keyCode - KeyEvent.KEYCODE_0)).toString())
                true
            }
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> {
                binding.query.append(('A' + (keyCode - KeyEvent.KEYCODE_A)).toString())
                true
            }
            else -> false
        }
    }

    private fun refresh() {
        val all = viewModel.listModel
        val q = binding.query.text.toString().trim()
        val items: List<TVModel> = if (q.isEmpty()) {
            recentModels(all)
        } else {
            all.filter { it.tv.title.contains(q, ignoreCase = true) }
        }
        listModel.setTVListModel(items)
        // 正在播放标识：当前频道在结果中时高亮
        val current = viewModel.groupModel.getCurrentTitle()
        val playingIdx = items.indexOfFirst { it.tv.title == current }
        listModel.setPositionPlaying(if (playingIdx >= 0) playingIdx else 0)

        adapter?.submitList(items)
        binding.sectionTitle.text = if (q.isEmpty()) {
            getString(R.string.recent_watch)
        } else {
            getString(R.string.search_channels) + "（${items.size}）"
        }
        if (items.isEmpty()) {
            binding.list.visibility = View.GONE
            binding.empty.visibility = View.VISIBLE
            binding.empty.text = getString(
                if (q.isEmpty()) R.string.no_recent else R.string.no_search_result
            )
        } else {
            binding.list.visibility = View.VISIBLE
            binding.empty.visibility = View.GONE
        }
    }

    private fun showKeyboard() {
        binding.query.requestFocus()
        (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(binding.query, 0)
    }

    private fun hideKeyboard() {
        (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(binding.query.windowToken, 0)
    }

    private fun focusResults() {
        if (binding.list.visibility == View.VISIBLE) {
            binding.list.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                ?: binding.list.requestFocus()
        }
    }

    /** 最近观看：SP 持久化的 stable id → 全部频道中的 TVModel（时间倒序，最多 12 条） */
    private fun recentModels(all: List<TVModel>): List<TVModel> {
        val byId = all.associateBy { it.tv.id.toString() }
        return SP.getRecentChannels().mapNotNull { byId[it] }.take(12)
    }

    private fun hideSelf() {
        shown = false
        hideKeyboard()
        try {
            requireActivity().supportFragmentManager.beginTransaction()
                .hide(this)
                .commitAllowingStateLoss()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "hideSelf failed: ${e.message}")
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) onShow() else if (_binding != null) hideKeyboard()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        adapter = null
    }

    companion object {
        private const val TAG = "SearchFragment"
    }
}
