package com.horsenma.yourtv

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.CheckedTextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import androidx.core.content.ContextCompat

private const val PING_PENDING = -2

class SourceSelectFragment : Fragment() {

    private lateinit var viewModel: MainViewModel
    private lateinit var channelNameText: TextView
    private lateinit var sourceCountText: TextView
    private lateinit var currentSourceText: TextView
    private lateinit var sourceRecyclerView: RecyclerView
    private lateinit var sourceAdapter: SourceAdapter
    private val handler = Handler(Looper.getMainLooper())
    private val hideDelay = 30_000L // 10秒后自动隐藏
    private lateinit var onSourceSelected: (Int, Boolean) -> Unit

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = (requireActivity() as MainActivity).getViewModel()
    }

    @SuppressLint("GestureBackNavigation")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_source_select, container, false)
        channelNameText = view.findViewById(R.id.channel_name)
        sourceCountText = view.findViewById(R.id.source_count)
        currentSourceText = view.findViewById(R.id.current_source)
        sourceRecyclerView = view.findViewById(R.id.source_list)

        // 设置 RecyclerView 属性
        sourceRecyclerView.isFocusable = true
        sourceRecyclerView.isFocusableInTouchMode = true
        sourceRecyclerView.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

        // 设置根布局点击监听
        val rootView = view.findViewById<LinearLayout>(R.id.source_select_root)
        val contentView = view.findViewById<LinearLayout>(R.id.content)
        rootView.setOnClickListener {
            val contentRect = android.graphics.Rect()
            contentView.getGlobalVisibleRect(contentRect)
            val x = it.x.toInt()
            val y = it.y.toInt()
            if (!contentRect.contains(x, y)) {
                hideSelf()
            }
        }

        // 初始化 onSourceSelected
        onSourceSelected = { index, _ ->
            val tvModel = (requireActivity() as MainActivity).playerFragment.tvModel
            if (tvModel != null) {
                // The panel is an exact line picker. Cycling here used to move
                // once more inside switchSource and play index + 1.
                (requireActivity() as MainActivity).playerFragment.selectSource(tvModel, index)
                sourceAdapter.updateSelection(index)
                hideSelf()
            } else {
                Log.w("SourceSelectFragment", "onSourceSelected: tvModel is null, skipping source selection")
            }
        }

        // 初始化 RecyclerView
        sourceRecyclerView.layoutManager = LinearLayoutManager(context)
        sourceAdapter = SourceAdapter(emptyList(), requireContext(), onSourceSelected)
        sourceRecyclerView.adapter = sourceAdapter

        // 设置按键监听
        view.isFocusableInTouchMode = true
        view.isFocusable = true
        view.requestFocus()
        view.setOnKeyListener { _, keyCode, event ->
            if (event?.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                        hideSelf()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        scheduleAutoHide()
                        false
                    }
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        scheduleAutoHide() // 重置自动隐藏计时
                        false
                    }
                    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                        val currentPosition = (sourceRecyclerView.layoutManager as LinearLayoutManager)
                            .findFirstCompletelyVisibleItemPosition()
                        if (currentPosition >= 0) {
                            sourceAdapter.videoIndexAt(currentPosition)?.let { videoIndex ->
                                onSourceSelected(videoIndex, true)
                            }
                        }
                        scheduleAutoHide()
                        true
                    }
                    else -> false
                }
            } else {
                false
            }
        }

        return view
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            Log.d("SourceSelectFragment", "Fragment shown, updating UI")
            updateUI()
            scheduleAutoHide()
            initializeFocus()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.channelsOk.observe(viewLifecycleOwner) { loaded ->
            if (loaded) {
                Log.d("SourceSelectFragment", "Channels loaded, updating UI")
                updateUI()
                scheduleAutoHide()
                initializeFocus()
            } else {
                Log.d("SourceSelectFragment", "Channels not loaded yet")
                channelNameText.text = getString(R.string.loading)
                sourceCountText.text = getString(R.string.total_sources, 0)
                currentSourceText.text = getString(R.string.current_source, 0)
            }
        }

        if (viewModel.channelsOk.value == true) {
            Log.d("SourceSelectFragment", "Channels already loaded, updating UI")
            updateUI()
            scheduleAutoHide()
            initializeFocus()
        }
    }

    private fun initializeFocus() {
        view?.post {
            if (!isAdded || !isVisible) return@post
            sourceRecyclerView.isFocusable = true
            sourceRecyclerView.isFocusableInTouchMode = true
            val model = (requireActivity() as MainActivity).playerFragment.tvModel
            val selectedVideoIndex = model?.videoIndexValue ?: 0
            val selectedIndex = model?.tv?.uris
                ?.withIndex()
                ?.filter { it.value.isNotBlank() }
                ?.indexOfFirst { it.index == selectedVideoIndex }
                ?.coerceAtLeast(0) ?: 0
            sourceRecyclerView.smoothScrollToPosition(selectedIndex)
            view?.postDelayed({
                if (!isAdded || !isVisible) return@postDelayed
                val holder = sourceRecyclerView.findViewHolderForAdapterPosition(selectedIndex)
                if (holder != null) {
                    val choice = holder.itemView.findViewById<CheckedTextView>(R.id.source_switch)
                    choice.isFocusable = true
                    choice.isFocusableInTouchMode = true
                    choice.requestFocus()
                    Log.d("SourceSelectFragment", "Focus set on line choice at position $selectedIndex")
                } else {
                    sourceRecyclerView.requestFocus()
                    Log.w("SourceSelectFragment", "ViewHolder not found for position $selectedIndex, fallback to RecyclerView")
                }
            }, 500) // 增加延迟到500ms
        }
    }

    private fun updateUI() {
        val tvModel = (requireActivity() as MainActivity).playerFragment.tvModel ?: run {
            Log.w("SourceSelectFragment", "updateUI: PlayerFragment's tvModel is null")
            channelNameText.text = getString(R.string.no_channel_data)
            sourceCountText.text = getString(R.string.total_sources, 0)
            currentSourceText.text = getString(R.string.current_source, 0)
            sourceAdapter.updateSources(emptyList(), -1)
            return
        }
        val sources = tvModel.tv.uris.withIndex().filter { it.value.isNotBlank() }
        val selectedPosition = sources.indexOfFirst { it.index == tvModel.videoIndexValue }
        Log.d("SourceSelectFragment", "updateUI: Channel=${tvModel.tv.title}, uris=${tvModel.tv.uris}, filtered sources=$sources, videoIndexValue=${tvModel.videoIndexValue}")
        channelNameText.text = getString(R.string.channel_name_with_tip, tvModel.tv.title)
        sourceCountText.text = getString(R.string.total_sources, sources.size)
        currentSourceText.text = getString(R.string.current_source, selectedPosition + 1)
        sourceAdapter.updateSources(sources.mapIndexed { displayIndex, source ->
            val url = source.value
            SourceInfo(
                displayIndex + 1,
                source.index,
                url,
                SourceSelection.resolution(url)?.let(::formatResolution) ?: getString(R.string.unknown),
                LineHealth.latency(url)?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: PING_PENDING,
                SP.getStableSources().any { it.uris.contains(url) },
                source.index == tvModel.videoIndexValue,
                // v3.3.0：线路来源标注（聚合反向关联源），换线面板直接可见
                tvModel.tv.uriSources[url]?.let { sourceNameOf(it) } ?: "",
                LineHealth.healthRank(url),
            )
        }, tvModel.videoIndexValue)
    }

    private fun formatResolution(resolution: String?): String {
        if (resolution == null || !resolution.matches("\\d+x\\d+".toRegex())) {
            return getString(R.string.unknown)
        }
        val (width, height) = resolution.split("x").map { it.toIntOrNull() ?: 0 }
        return when {
            width >= 3840 && height >= 2160 -> "4K"
            width >= 2560 && height >= 1440 -> "2K"
            width >= 1920 && height >= 1080 -> "1080p"
            width >= 1280 && height >= 720 -> "720p"
            width >= 854 && height >= 480 -> "480p"
            width >= 640 && height >= 360 -> "360p"
            width >= 426 && height >= 240 -> "240p"
            else -> getString(R.string.unknown)
        }
    }

    /** 源名精简：取域名最后两段（raw.githubusercontent.com/CCSH/IPTV → CCSH） */
    private fun sourceNameOf(url: String): String {
        val lower = url.lowercase()
        return when {
            "zbds.top" in lower -> "爱直播"
            "vbskycn" in lower -> "vbskycn"
            "ccsh" in lower -> "CCSH"
            "best-fan" in lower -> "best-fan"
            "yuechan" in lower -> "YueChan"
            "yangg-1989" in lower -> "YanG-1989"
            "migu-sports" in lower -> "咪咕体育"
            "iptv-org" in lower -> "iptv-org"
            "aptv" in lower -> "aptv"
            "jk2024988" in lower -> "咪咕2"
            "hujingguang" in lower -> "ChinaIPTV"
            "fanmingming" in lower -> "fanmingming"
            "suxuang" in lower -> "myIPTV"
            else -> {
                val host = Regex("https?://([^/]+)").find(url)?.groupValues?.get(1).orEmpty()
                host.removePrefix("www.").take(20)
            }
        }
    }

    private fun scheduleAutoHide() {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (isAdded && !isHidden && sourceRecyclerView.findFocus() == null) {
                hideSelf()
            }
        }, hideDelay)
    }

    fun hideSelf() {
        if (isAdded && !isHidden) {
            requireActivity().supportFragmentManager.beginTransaction()
                .hide(this)
                .commitAllowingStateLoss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
    }
}

data class SourceInfo(
    val index: Int,
    val videoIndex: Int,
    val url: String,
    val resolution: String,
    val ping: Int,
    val isStable: Boolean,
    val isSelected: Boolean = false,
    /** v3.3.0：线路来源（聚合反向关联源），换线面板直接展示 */
    val sourceName: String = "",
    val healthRank: Int = 1,
)

class SourceAdapter(
    private var sources: List<SourceInfo>,
    private val context: android.content.Context,
    private val onSourceSelected: (Int, Boolean) -> Unit
) : RecyclerView.Adapter<SourceAdapter.SourceViewHolder>() {

    inner class SourceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val sourceChoice: CheckedTextView = itemView.findViewById(R.id.source_switch)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SourceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_source, parent, false)
        return SourceViewHolder(view)
    }

    override fun onBindViewHolder(holder: SourceViewHolder, position: Int) {
        val source = sources[position]
        val pingText = when {
            source.healthRank >= 3 && source.ping >= 0 -> context.getString(R.string.line_status_played, source.ping)
            source.healthRank >= 3 -> context.getString(R.string.line_status_played_no_latency)
            source.healthRank >= 2 -> context.getString(R.string.line_status_available, source.ping.coerceAtLeast(0))
            source.healthRank == 0 || source.ping == -1 -> context.getString(R.string.line_status_unavailable)
            else -> context.getString(R.string.line_status_pending)
        }
        // 设置 ping 值的底色和文字颜色
        val (backgroundColor, textColor) = when {
            source.healthRank == 0 || source.ping == -1 -> Pair(0xFFB3261E.toInt(), 0xFFFFFFFF.toInt())
            source.healthRank >= 2 || source.ping in 0..800 -> Pair(0xFF1B5E20.toInt(), 0xFFFFFFFF.toInt())
            else -> Pair(0xFF455A64.toInt(), 0xFFFFFFFF.toInt())
        }

        // 创建 SpannableString 设置 ping 部分的颜色
        val stableText = if (source.isStable) context.getString(R.string.line_status_favorite) else ""
        val details = listOf(source.sourceName, stableText).filter { it.isNotBlank() }.joinToString(" · ")
        val text = context.getString(R.string.source_info, source.index, source.resolution, pingText) +
            if (details.isBlank()) "" else "\n$details"
        val spannable = SpannableString(text)
        val pingStart = text.indexOf(pingText)
        val pingEnd = pingStart + pingText.length
        if (pingStart >= 0) {
            spannable.setSpan(BackgroundColorSpan(backgroundColor), pingStart, pingEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(ForegroundColorSpan(textColor), pingStart, pingEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        holder.sourceChoice.text = spannable
        holder.sourceChoice.isChecked = source.isSelected
        holder.sourceChoice.setOnClickListener {
            onSourceSelected(source.videoIndex, true)
        }

        // 增强焦点文字反馈
        holder.sourceChoice.setOnFocusChangeListener { _, hasFocus ->
            holder.sourceChoice.setTextColor(
                ContextCompat.getColor(context, if (hasFocus) R.color.focus else R.color.title_blur)
            )
            if (hasFocus) {
                holder.sourceChoice.text = spannable
            }
        }

    }

    override fun getItemCount(): Int = sources.size

    fun videoIndexAt(position: Int): Int? = sources.getOrNull(position)?.videoIndex

    fun updateSources(newSources: List<SourceInfo>, currentIndex: Int) {
        sources = newSources.map { source ->
            source.copy(isSelected = source.videoIndex == currentIndex)
        }
        notifyDataSetChanged()
    }

    // 新增方法：更新选中状态
    fun updateSelection(newSelectedIndex: Int) {
        sources = sources.map { source ->
            source.copy(isSelected = source.videoIndex == newSelectedIndex)
        }
        notifyDataSetChanged()
    }
}
