package com.horsenma.yourtv

import android.annotation.SuppressLint
import android.view.GestureDetector
import android.view.MotionEvent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.media3.common.MimeTypes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.DISCONTINUITY_REASON_AUTO_TRANSITION
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import com.horsenma.yourtv.data.SourceType
import com.horsenma.yourtv.databinding.PlayerBinding
import com.horsenma.yourtv.models.TVModel
import com.horsenma.yourtv.requests.HttpClient
import androidx.media3.ui.PlayerView
import com.horsenma.yourtv.data.StableSource
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.horsenma.yourtv.data.TV
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Rational
import androidx.core.view.isVisible
import com.horsenma.yourtv.data.PlayerType
import com.horsenma.mytv1.WebFragmentCallback
import android.view.Gravity
import android.widget.TextView
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.hls.HlsMediaSource


class PlayerFragment : Fragment() {
    private lateinit var viewModel: MainViewModel
    fun setViewModel(viewModel: MainViewModel) {
        this.viewModel = viewModel
    }
    private val stablePlaybackDuration = 30_000L
    private var isStable = false
    private var _binding: PlayerBinding? = null
    private val binding get() = _binding!!
    internal var player: ExoPlayer? = null
    internal var tvModel: TVModel? = null
    // 备用播放器：后台预准备"下一频道"，切台时无缝接管
    private var standbyPlayer: ExoPlayer? = null
    private var standbyTargetUrl: String? = null
    private var standbyChannelId = -1
    private var standbyReady = false
    private val aspectRatio = 16f / 9f
    internal var isInPictureInPictureMode = false
    private val handler = Handler(Looper.myLooper()!!)
    private val delayHideVolume = 2 * 1000L
    // 新增：缓冲检测变量
    private val bufferingThreshold = 5
    private val bufferingDurationThreshold = 8_000L
    private val switchCooldown = 15_000L
    private val stablePlaybackThreshold = 10_000L
    private var bufferingStartTime = 0L
    private var bufferingCount = 0
    private var lastSwitchTime = 0L
    private var playbackStartTime = 0L
    private val bufferingTimestamps = mutableListOf<Long>()
    private var lastBufferingTime = 0L
    private var isSourceButtonVisible = false
    private var lastSwitchSourceTime = 0L
    private val switchSourceDebounce = 2_000L
    private var lastFallbackTime = 0L
    private val fallbackCooldown = 10_000L
    // 新增：播放停止检测变量
    private var lastStopTime = 0L
    private val stopDurationThreshold = 2_500L
    private val retryCooldown = 30_000L
    private val checkPlaybackInterval = 15_000L
    // 定义保存间隔（例如 5 分钟，防止频繁保存）
    private var lastPauseTime = 0L
    private val stableSourceCheckRunnable = Runnable {
        if (player?.isPlaying == true && tvModel != null &&
            System.currentTimeMillis() - playbackStartTime >= stablePlaybackDuration &&
            bufferingCount == 0 && tvModel!!.retryTimes == 0) {
            isStable = true
            saveStableSource(tvModel!!)
            Log.d(TAG, "Stable source saved via stableSourceCheckRunnable: ${tvModel!!.tv.title}")
        }
    }

    @OptIn(UnstableApi::class)
    fun enterPictureInPictureMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.d(TAG, "Picture-in-Picture mode not supported on API ${Build.VERSION.SDK_INT}")
            return
        }
        if (!isTouchScreenDevice()) {
            Log.d(TAG, "Picture-in-Picture mode skipped: Not a touchscreen device")
            return
        }
        // 获取视频的实际宽高比
        val aspectRatio = if (tvModel?.tv?.playerType == PlayerType.WEBVIEW) {
            Rational(16, 9)
        } else {
            val videoSize = player?.videoSize
            if (videoSize != null && videoSize.width > 0 && videoSize.height > 0) {
                val ratio = videoSize.width.toFloat() / videoSize.height
                when {
                    ratio > 2.39f -> Rational(239, 100)
                    ratio < 1 / 2.39f -> Rational(100, 239)
                    else -> Rational(videoSize.width, videoSize.height)
                }
            } else {
                Rational(16, 9)
            }
        }

        val params = PictureInPictureParams.Builder()
            .setAspectRatio(aspectRatio)
            .build()
        try {
            requireActivity().enterPictureInPictureMode(params)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Failed to enter Picture-in-Picture mode: ${e.message}")
            return
        }
        if (_binding != null) {
            if (tvModel?.tv?.playerType == PlayerType.WEBVIEW) {
                binding.webView.layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ).apply {
                    gravity = Gravity.CENTER
                }
                binding.webView.visibility = View.VISIBLE
                binding.playerView.visibility = View.GONE
                binding.webView.requestLayout()
                binding.webView.requestFocus()

                childFragmentManager.findFragmentById(R.id.web_view)?.let { fragment ->
                    if (fragment is com.horsenma.mytv1.WebFragment) {
                        fragment.injectScalingCssForPiP()
                    }
                }
            } else {
                binding.playerView.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                binding.playerView.useController = false
                binding.playerView.layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ).apply {
                    gravity = Gravity.CENTER
                }
                binding.playerView.requestLayout()
                binding.playerView.requestFocus()
                if (player == null && tvModel != null) {
                    updatePlayer()
                    Log.d(TAG, "Player was null, reinitialized for ${tvModel!!.tv.title}")
                }
                if (player?.isPlaying == false && tvModel != null) {
                    player?.prepare()
                    player?.playWhenReady = true
                    Log.d(TAG, "enterPictureInPictureMode: Playback resumed for ${tvModel!!.tv.title}")
                }
            }
            setSourceButtonVisibility(false)
            binding.icon.visibility = View.GONE
            binding.volume.visibility = View.GONE
            binding.playerView.clearFocus()
        }
        isInPictureInPictureMode = true
        Log.d(TAG, "Entered Picture-in-Picture mode with aspectRatio=$aspectRatio, playerType=${tvModel?.tv?.playerType}")
    }

    @OptIn(UnstableApi::class)
    fun exitPictureInPictureMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.d(TAG, "Picture-in-Picture mode not supported on API ${Build.VERSION.SDK_INT}")
            return
        }
        if (_binding != null) {
            isInPictureInPictureMode = false
            setSourceButtonVisibility(isTouchScreenDevice() && SP.showSourceButton)
            onFullScreenModeChanged()
            Log.d(TAG, "Exiting Picture-in-Picture mode, btn_source visible=${binding.btnSource.isVisible}")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = PlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        try {
            updatePlayer()
            binding.playerView.isFocusable = true
            binding.playerView.isFocusableInTouchMode = true
            binding.playerView.requestFocus()
            Log.d(TAG, "PlayerView focus requested: isFocusable=${binding.playerView.isFocusable}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize PlayerFragment view: ${e.message}", e)
        }
        updatePlayer()
        (activity as MainActivity).ready()

        val btnSource = view.findViewById<Button>(R.id.btn_source)
        // 初始化 btn_source 可见性
        setSourceButtonVisibility(isTouchScreenDevice() && SP.showSourceButton)
        Log.d(TAG, "btn_source initialized: visibility=${btnSource.isVisible}, isTouchScreen=${isTouchScreenDevice()}, showSourceButton=${SP.showSourceButton}")

        // 设置 btn_source 的双击手势监听
        val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (btnSource.isEnabled && btnSource.isVisible) {
                    (activity as? MainActivity)?.sourceUp()
                    Log.d(TAG, "btn_source double tapped, triggering sourceUp")
                    return true
                }
                return false
            }
            override fun onLongPress(e: MotionEvent) {
                if (btnSource.isEnabled && btnSource.isVisible) {
                    val mainActivity = activity as? MainActivity
                    mainActivity?.showFragment(mainActivity.sourceSelectFragment)
                    Log.d(TAG, "btn_source long pressed, showing SourceSelectFragment")
                }
            }
        })

        // 确保 btn_source 优先接收触摸事件
        btnSource.setOnTouchListener { _, event ->
            if (btnSource.isEnabled && btnSource.isVisible) {
                gestureDetector.onTouchEvent(event)
                true // 消耗事件，防止 PlayerView 拦截
            } else {
                false // 不可见或禁用时透传事件
            }
        }

        // 防止 PlayerView 拦截 btn_source 的事件
        binding.playerView.setOnTouchListener { _, event ->
            val buttonRect = android.graphics.Rect()
            btnSource.getGlobalVisibleRect(buttonRect)
            if (btnSource.isVisible && buttonRect.contains(event.rawX.toInt(), event.rawY.toInt())) {
                btnSource.dispatchTouchEvent(event)
                true
            } else {
                // 传递给 MainActivity 的 gestureDetector
                (activity as? MainActivity)?.gestureDetector?.onTouchEvent(event) ?: false
                true // 始终消耗事件
            }
        }
    }

    // 控制 btn_source 可见性
    @OptIn(UnstableApi::class)
    fun setSourceButtonVisibility(visible: Boolean) {
        val btnSource = binding.root.findViewById<Button>(R.id.btn_source) ?: return
        val shouldShow = if (!visible) {
            false // 画中画模式下始终隐藏
        } else {
            isTouchScreenDevice() && SP.showSourceButton
        }
        btnSource.visibility = if (shouldShow) View.VISIBLE else View.GONE
        btnSource.isFocusable = shouldShow
        btnSource.isEnabled = shouldShow
        btnSource.isFocusableInTouchMode = shouldShow // 确保触摸交互
        isSourceButtonVisible = shouldShow
        Log.d(TAG, "setSourceButtonVisibility: visible=$visible, shouldShow=$shouldShow, isTouchScreen=${isTouchScreenDevice()}, showSourceButton=${SP.showSourceButton}, btnSource.focusable=${btnSource.isFocusable}")
    }

    // 新增：播放状态回调接口
    interface PlaybackCallback {
        fun onPlaybackStarted()
    }

    private var playbackCallback: PlaybackCallback? = null

    // 新增：设置回调
    fun setPlaybackCallback(callback: PlaybackCallback) {
        this.playbackCallback = callback
    }

    @OptIn(UnstableApi::class)
    fun updatePlayer() {
        if (context == null) {
            Log.e(TAG, "context == null")
            return
        }

        val ctx = requireContext()
        val playerView = binding.playerView
        val renderersFactory = DefaultRenderersFactory(ctx)
        val playerMediaCodecSelector = PlayerMediaCodecSelector()
        renderersFactory.setMediaCodecSelector(playerMediaCodecSelector)
        renderersFactory.setExtensionRendererMode(
            if (SP.softDecode) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
        )

        if (player != null) {
            player?.release()
        }

        player = ExoPlayer.Builder(ctx)
            .setRenderersFactory(renderersFactory)
            .setLoadControl(createFastLoadControl())
            .build()
        player?.repeatMode = REPEAT_MODE_ALL
        player?.playWhenReady = true
        player?.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (!isInPictureInPictureMode) {
                    updatePlayerViewLayout() // Call new method to handle layout
                }
                Log.d(TAG, "Video size changed: ${videoSize.width}x${videoSize.height}")
                // 缓存实际分辨率，供线路"高清稳定优先"排序
                if (videoSize.width > 0 && videoSize.height > 0) {
                    tvModel?.getVideoUrl()?.let { url ->
                        SP.cacheResolution(url, "${videoSize.width}x${videoSize.height}")
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (tvModel == null) {
                    Log.e(TAG, "tvModel == null")
                    return
                }

                val tv = tvModel!!
                if (isPlaying) {
                    tv.confirmSourceType()
                    tv.confirmVideoIndex()
                    tv.setErrInfo("")
                    tv.retryTimes = 0
                    bufferingCount = 0
                    bufferingStartTime = 0L
                    bufferingTimestamps.clear()
                    lastBufferingTime = 0L
                    playbackStartTime = System.currentTimeMillis()
                    playbackCallback?.onPlaybackStarted()
                    lastStopTime = 0L // 重置停止时间
                    Log.d(TAG, "${tv.tv.title} is playing")
                    prepareStandbyForNextChannel()

                } else {
                    isStable = false
                    playbackStartTime = 0L // 重置计时
                    lastStopTime = System.currentTimeMillis() // 记录停止时间
                    Log.i(TAG, "${tv.tv.title} 播放停止")
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (!SP.autoSwitchSource) return
                if (tvModel == null || player == null) {
                    return
                }

                val currentTime = System.currentTimeMillis()

                // 检查是否处于播放稳定期（启动或切换源后10秒内不监控缓冲）
                if (currentTime - playbackStartTime < stablePlaybackThreshold) {
                    if (state == Player.STATE_READY) {
                        // 播放稳定后更新开始时间
                        playbackStartTime = currentTime
                    }
                    return
                }

                // 检测缓冲状态
                if (state == Player.STATE_BUFFERING) {
                    // 过滤快速重复缓冲（小于500ms的忽略）
                    if (currentTime - lastBufferingTime < 500L) {
                        return
                    }

                    if (bufferingStartTime == 0L) {
                        bufferingStartTime = currentTime
                    }
                    lastBufferingTime = currentTime
                    bufferingTimestamps.add(currentTime)
                    // 统计最近10秒内的缓冲次数
                    bufferingCount = bufferingTimestamps.count { it >= currentTime - 10_000L }
                    val bufferingDuration = currentTime - bufferingStartTime

                    // 清理过旧的时间戳
                    bufferingTimestamps.removeAll { it < currentTime - 10_000L }

                    // 检查是否需要切换源
                    if ((bufferingCount >= bufferingThreshold && currentTime - lastSwitchTime >= switchCooldown) ||
                        (bufferingDuration >= bufferingDurationThreshold && currentTime - lastSwitchTime >= switchCooldown)) {
                        if (tvModel!!.retryTimes < tvModel!!.retryMaxTimes && player!!.currentPosition > 0) {
                            Log.i(TAG, "Non-smooth playback detected: bufferingCount=$bufferingCount, duration=$bufferingDuration")
                            (activity as MainActivity).sourceUp(false)
                            lastSwitchTime = currentTime
                            playbackStartTime = currentTime // 重置播放开始时间
                            bufferingCount = 0
                            bufferingStartTime = 0L
                            bufferingTimestamps.clear()
                            lastBufferingTime = 0L
                        }
                    }
                } else if (state == Player.STATE_READY) {
                    // 播放流畅时重置缓冲变量（如果持续流畅超过2秒）
                    if (currentTime - lastBufferingTime >= 2_000L) {
                        bufferingStartTime = 0L
                        bufferingCount = 0
                        bufferingTimestamps.clear()
                        lastBufferingTime = 0L
                    }
                } else if (state == Player.STATE_ENDED) {
                    // 播放结束时重置所有变量
                    bufferingStartTime = 0L
                    bufferingCount = 0
                    bufferingTimestamps.clear()
                    lastBufferingTime = 0L
                    playbackStartTime = 0L
                    lastStopTime = currentTime // 记录停止时间
                    Log.w(TAG, "${tvModel!!.tv.title} playback ended, marking for retry, lastStopTime=$lastStopTime, cooldownRemaining=${if (currentTime - lastSwitchTime < retryCooldown) retryCooldown - (currentTime - lastSwitchTime) else 0}")
                    // 优化：立即触发重试
                    if (!isInPictureInPictureMode) {
                        Log.w(TAG, "${tvModel!!.tv.title} ended, retrying immediately")
                        switchSource(tvModel!!)
                        lastSwitchTime = currentTime
                        lastStopTime = 0L
                    }
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (reason == DISCONTINUITY_REASON_AUTO_TRANSITION) {
                    (activity as MainActivity).onPlayEnd()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "Player error: ${error.errorCode}, message=${error.message}")
                // 播放失败：立即标记线路不可用，后续切换跳过
                tvModel?.getVideoUrl()?.let { LineHealth.mark(it, false) }
                if (tvModel?.tv?.playerType == PlayerType.WEBVIEW) {
                    // 仅忽略非网络相关错误，网络错误仍需触发切换
                    if (error.errorCode !in listOf(
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                        )) {
                        Log.d(TAG, "Ignored non-network ExoPlayer error for WEBVIEW: ${tvModel?.tv?.title}, error=${error.message}")
                        return
                    }
                }
                lastStopTime = System.currentTimeMillis()
                Log.w(TAG, "Marking for retry: lastStopTime=$lastStopTime, cooldownRemaining=${if (System.currentTimeMillis() - lastSwitchTime < retryCooldown) retryCooldown - (System.currentTimeMillis() - lastSwitchTime) else 0}")
                if (System.currentTimeMillis() - lastSwitchTime >= retryCooldown) {
                    Log.w(TAG, "${tvModel?.tv?.title} error, retrying immediately")
                    tvModel?.let { switchSource(it) }
                    lastSwitchTime = System.currentTimeMillis()
                    lastStopTime = 0L
                }
                // 首次使用检测：无稳定源且 cacheFile 不存在
                // val isFirstUse = SP.getStableSources().isEmpty() && !File(requireContext().filesDir, "cacheFile").exists()
                val isFirstUse = SP.getStableSources().isEmpty()
                if (isFirstUse && tvModel != null) {
                    if (tvModel!!.retryTimes < 3) { // 限制为 3 次
                        tvModel!!.nextSourceType() // 尝试下一个源类型
                        tvModel!!.setReady(true)
                        tvModel!!.retryTimes++
                        Log.i(TAG, "First use: Error detected, switching source for ${tvModel!!.tv.title}")
                        (activity as MainActivity).sourceUp(false)
                        lastSwitchTime = System.currentTimeMillis()
                        // 快速超时：3 秒后若未播放，触发下一次切换
                        handler.postDelayed({
                            if (player?.isPlaying != true) {
                                (activity as MainActivity).sourceUp(false)
                                Log.i(TAG, "First use: 3s timeout, retry switching for ${tvModel!!.tv.title}")
                            }
                        }, 3_000L)
                        return
                    } else if (!tvModel!!.isLastVideo()) {
                        tvModel!!.nextVideo() // 尝试下一个视频源
                        tvModel!!.setReady(true)
                        tvModel!!.retryTimes = 0
                        Log.i(TAG, "First use: All source types failed, switching video for ${tvModel!!.tv.title}")
                        (activity as MainActivity).sourceUp(false)
                        lastSwitchTime = System.currentTimeMillis()
                        handler.postDelayed({
                            if (player?.isPlaying != true) {
                                (activity as MainActivity).sourceUp(false)
                                Log.i(TAG, "First use: 3s timeout, retry switching for ${tvModel!!.tv.title}")
                            }
                        }, 3_000L)
                        return
                    } else {
                        // Fallback 冷却：避免网络差时频道连续漂移
                        if (System.currentTimeMillis() - lastFallbackTime < fallbackCooldown) {
                            Log.w(TAG, "Fallback cooldown active, stop for ${tvModel!!.tv.title}")
                            tvModel!!.setErrInfo(R.string.play_error.getString())
                            return
                        }
                        lastFallbackTime = System.currentTimeMillis()
                        // 所有源无效，尝试下一个频道
                        val nextChannel = viewModel.groupModel.getNext()
                        if (nextChannel != null) {
                            nextChannel.setReady()
                            viewModel.groupModel.setCurrent(nextChannel)
                            switchSource(nextChannel)
                            Log.d(TAG, "First use: Fell back to next channel: ${nextChannel.tv.title}")
                            lastSwitchTime = System.currentTimeMillis()
                            handler.postDelayed({
                                if (player?.isPlaying != true) {
                                    (activity as MainActivity).sourceUp(false)
                                    Log.i(TAG, "First use: 3s timeout, retry switching for ${nextChannel.tv.title}")
                                }
                            }, 3_000L)
                            return
                        } else {
                            tvModel!!.setErrInfo(R.string.play_error.getString())
                            Log.w(TAG, "First use: No next channel available")
                            return
                        }
                    }
                }

                if (!SP.autoSwitchSource) {
                    Log.w(TAG, "Auto-switch disabled, ignoring error: ${error.message}")
                    return
                }
                if (tvModel == null) {
                    Log.e(TAG, "tvModel == null")
                    return
                }

                if (error.errorCode !in listOf(
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                    )) {
                    Log.w(TAG, "Non-supported error: ${error.errorCode}, ignoring")
                    return
                }

                val tv = tvModel!!
                if (tv.retryTimes < tv.retryMaxTimes) {
                    var last = true
                    if (tv.getSourceTypeDefault() == SourceType.UNKNOWN) {
                        last = tv.nextSourceType()
                    }
                    tv.setReady(true)
                    if (last) {
                        tv.retryTimes++
                    }
                    Log.i(
                        TAG,
                        "Retry ${tv.videoIndex.value} ${tv.getSourceTypeCurrent()} ${tv.retryTimes}/${tv.retryMaxTimes}"
                    )
                    if (System.currentTimeMillis() - lastSwitchTime >= switchCooldown) {
                        handler.postDelayed({
                            if (player?.isPlaying != true) {
                                (activity as MainActivity).sourceUp(false)
                            } else {
                                Log.d(TAG, "Playback recovered, no need to switch")
                            }
                        }, 2_000L)
                        lastSwitchTime = System.currentTimeMillis()
                    }
                } else {
                    if (!tv.isLastVideo()) {
                        tv.nextVideo()
                        tv.setReady(true)
                        tv.retryTimes = 0
                        (activity as MainActivity).sourceUp(false)
                    } else {
                        // Fallback to stable source
                        lifecycleScope.launch(Dispatchers.Main) {
                            val stableSource = selectRandomStableSource()
                            if (stableSource != null) {
                                val newTvModel = TVModel(
                                    TV(
                                        id = stableSource.id,
                                        name = stableSource.name,
                                        title = stableSource.title,
                                        description = stableSource.description,
                                        logo = stableSource.logo,
                                        image = stableSource.image,
                                        uris = stableSource.uris,
                                        videoIndex = stableSource.videoIndex,
                                        headers = stableSource.headers,
                                        group = stableSource.group,
                                        sourceType = SourceType.valueOf(stableSource.sourceType),
                                        number = stableSource.number,
                                        child = stableSource.child,
                                        playerType = stableSource.playerType,
                                        block = stableSource.block,
                                        script = stableSource.script,
                                        selector = stableSource.selector,
                                        started = stableSource.started,
                                        finished = stableSource.finished
                                    )
                                ).apply {
                                    setLike(SP.getLike(stableSource.id))
                                    setGroupIndex(2)
                                    listIndex = 0
                                }
                                viewModel.groupModel.setCurrent(newTvModel)
                                switchSource(newTvModel)
                                Log.d(TAG, "Fell back to stable source: ${newTvModel.tv.title}, url: ${newTvModel.getVideoUrl()}")
                                return@launch
                            }
                            // No stable sources, try next channel
                            val nextChannel = viewModel.groupModel.getNext()
                            if (nextChannel != null) {
                                nextChannel.setReady()
                                viewModel.groupModel.setCurrent(nextChannel)
                                switchSource(nextChannel)
                                Log.d(TAG, "Fell back to next channel: ${nextChannel.tv.title}")
                            } else {
                                tv.setErrInfo(R.string.play_error.getString())
                                Log.w(TAG, "No stable sources or next channel available")
                            }
                        }
                    }
                }
            }
        })

        playerView.player = player
        tvModel?.let {
            play(it)
        }

        // 修复：移动定时器启动到末尾
        handler.removeCallbacks(checkPlaybackRunnable)
        handler.removeCallbacks(stableSourceCheckRunnable)
        handler.postDelayed(checkPlaybackRunnable, checkPlaybackInterval)
        handler.postDelayed(stableSourceCheckRunnable, stablePlaybackDuration)
    }

    @OptIn(UnstableApi::class)
    private fun updatePlayerViewLayout() {
        val playerView = binding.playerView
        val app = YourTVApplication.getInstance()
        val isFullScreen = SP.fullScreenMode

        // 全屏/非全屏都保持原始比例（留黑边），避免 4:3/21:9 源被拉伸变形
        playerView.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT

        val layoutParams = FrameLayout.LayoutParams(
            if (isFullScreen) ViewGroup.LayoutParams.MATCH_PARENT else app.videoWidthPx(),
            if (isFullScreen) ViewGroup.LayoutParams.MATCH_PARENT else app.videoHeightPx()
        ).apply {
            gravity = Gravity.CENTER // 确保居中
        }
        playerView.layoutParams = layoutParams

        playerView.requestLayout()
        playerView.post {
            Log.d(TAG, "Updated PlayerView layout: fullScreen=$isFullScreen, width=${layoutParams.width}, height=${layoutParams.height}, gravity=${layoutParams.gravity}")
        }
    }

    @OptIn(UnstableApi::class)
    fun onFullScreenModeChanged() {
        if (!isAdded || isInPictureInPictureMode || _binding == null) {
            Log.d(TAG, "onFullScreenModeChanged skipped: isAdded=$isAdded, isInPiP=$isInPictureInPictureMode, binding=${_binding}")
            return
        }
        val app = YourTVApplication.getInstance()
        val isFullScreen = SP.fullScreenMode
        if (tvModel?.tv?.playerType == PlayerType.WEBVIEW) {
            binding.webView.layoutParams = FrameLayout.LayoutParams(
                if (isFullScreen) ViewGroup.LayoutParams.MATCH_PARENT else app.videoWidthPx(),
                if (isFullScreen) ViewGroup.LayoutParams.MATCH_PARENT else app.videoHeightPx()
            ).apply {
                gravity = Gravity.CENTER
            }
            binding.webView.visibility = View.VISIBLE
            binding.playerView.visibility = View.GONE
            binding.webView.bringToFront() // 确保 WebView 在顶层
            binding.webView.requestLayout()
            binding.webView.post {
                Log.d(TAG, "web_view updated for fullScreenMode: fullScreen=$isFullScreen, width=${binding.webView.width}, height=${binding.webView.height}")
            }
            // 通知 WebFragment 更新布局
            childFragmentManager.findFragmentById(R.id.web_view)?.let { fragment ->
                if (fragment is com.horsenma.mytv1.WebFragment) {
                    fragment.updateWebViewLayout()
                }
            }
            binding.webView.requestFocus()
            binding.webView.isFocusable = true
            binding.webView.isFocusableInTouchMode = true
        } else {
            updatePlayerViewLayout()
            binding.playerView.visibility = View.VISIBLE
            binding.webView.visibility = View.GONE
            binding.playerView.requestFocus()
            binding.playerView.isFocusable = true
            binding.playerView.isFocusableInTouchMode = true
        }
        // 强制刷新整个布局
        binding.root.requestLayout()
        binding.root.requestFocus()
        // 验证窗口尺寸
        val displayMetrics = resources.displayMetrics
        Log.d(TAG, "onFullScreenModeChanged: fullScreen=$isFullScreen, videoWidthPx=${app.videoWidthPx()}, videoHeightPx=${app.videoHeightPx()}, screenWidth=${displayMetrics.widthPixels}, screenHeight=${displayMetrics.heightPixels}")
    }

    private val checkPlaybackRunnable = object : Runnable {
        @OptIn(UnstableApi::class)
        override fun run() {
            val currentTime = System.currentTimeMillis()
            if (tvModel == null || !isResumed) {
                Log.d(TAG, "Playback check skipped: tvModel=$tvModel, isResumed=$isResumed, isInPip=$isInPictureInPictureMode")
                handler.postDelayed(this, checkPlaybackInterval)
                return
            }
            if (isInPictureInPictureMode || (lastPauseTime > lastStopTime && currentTime - lastPauseTime < stopDurationThreshold)) {
                Log.d(TAG, "Playback check skipped: recent pause at $lastPauseTime or in PiP mode")
                handler.postDelayed(this, checkPlaybackInterval)
                return
            }
            val isPlaying = when (tvModel!!.tv.playerType) {
                PlayerType.WEBVIEW -> {
                    childFragmentManager.findFragmentById(R.id.web_view)?.let { fragment ->
                        (fragment as? com.horsenma.mytv1.WebFragment)?.let { webFragment ->
                            webFragment.isPlaying.also { playing ->
                                if (!playing && lastStopTime == 0L) {
                                    lastStopTime = System.currentTimeMillis()
                                    Log.d(TAG, "WEBVIEW playback stopped, marking lastStopTime=$lastStopTime")
                                }
                            }
                        } ?: false
                    } ?: false
                }
                PlayerType.IPTV -> {
                    player?.let {
                        (it.isPlaying == true && it.playbackState == Player.STATE_READY && it.playWhenReady == true).also { playing ->
                            if (!playing && lastStopTime == 0L) {
                                lastStopTime = System.currentTimeMillis()
                                Log.d(TAG, "IPTV playback stopped, marking lastStopTime=$lastStopTime")
                            }
                        }
                    } ?: false
                }
                else -> false
            }
            val stopDuration = if (lastStopTime > 0) currentTime - lastStopTime else 0L
            val cooldownRemaining = if (currentTime - lastSwitchTime < retryCooldown) {
                retryCooldown - (currentTime - lastSwitchTime)
            } else 0L
            Log.d(TAG, "Playback check: isPlaying=$isPlaying, lastStopTime=$lastStopTime, " +
                    "stopDuration=$stopDuration, cooldownRemaining=$cooldownRemaining, " +
                    "isResumed=$isResumed, isInPip=$isInPictureInPictureMode, playerType=${tvModel!!.tv.playerType}, " +
                    "bufferingCount=$bufferingCount, retryTimes=${tvModel!!.retryTimes}, " +
                    "playbackDuration=${if (playbackStartTime > 0) currentTime - playbackStartTime else 0L}")
            if (!isPlaying && lastStopTime > 0 && stopDuration >= stopDurationThreshold && cooldownRemaining == 0L) {
                if (tvModel?.tv?.playerType == PlayerType.WEBVIEW) {
                    // 网页源解析/加载慢（可达数十秒），停播检测不适用：
                    // 交给 WebFragment 内部超时重载，不在这里自动换线/弹错
                    Log.d(TAG, "WebView channel slow to start, skipping stop-based auto-switch")
                    lastStopTime = 0L
                } else {
                    Log.w(TAG, "${tvModel!!.tv.title} stopped for ${stopDurationThreshold / 1000}s, retrying")
                    // 停播超时：标记当前线路不可用
                    tvModel?.getVideoUrl()?.let { LineHealth.mark(it, false) }
                    switchSource(tvModel!!)
                    lastSwitchTime = currentTime
                    lastStopTime = 0L
                }
            } else if (isPlaying && stopDuration == 0L && cooldownRemaining == 0L) {
                // Check for stable source saving
                if (currentTime - playbackStartTime >= stablePlaybackDuration && // 播放持续 30 秒
                    bufferingCount == 0 && tvModel!!.retryTimes == 0) {
                    isStable = true
                    saveStableSource(tvModel!!)
                    Log.d(TAG, "Stable source saved: ${tvModel!!.tv.title}, playerType=${tvModel!!.tv.playerType}, isPlaying=$isPlaying")
                }
            }
            handler.postDelayed(this, checkPlaybackInterval)
        }
    }

    private fun selectRandomStableSource(): StableSource? {
        val stableSources = SP.getStableSources()
        return if (stableSources.isNotEmpty()) {
            stableSources.sortedByDescending { it.timestamp }.firstOrNull()
        } else {
            null
        }
    }

    private fun saveStableSource(tvModel: TVModel) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val currentUrl = tvModel.getVideoUrl() ?: run {
                    Log.w(TAG, "Failed to save stable source: ${tvModel.tv.title}, no valid URL")
                    return@launch
                }
                val tv = tvModel.tv
                Log.d(TAG, "Preparing to save stable source: ${tv.title}, videoIndex=${tvModel.videoIndexValue}, url=$currentUrl")
                val newSource = StableSource(
                    id = tv.id,
                    name = tv.name,
                    title = tv.title,
                    description = tv.description,
                    logo = tv.logo,
                    image = tv.image,
                    uris = listOf(currentUrl),
                    videoIndex = tvModel.videoIndexValue,
                    headers = tv.headers,
                    group = tv.group,
                    sourceType = tvModel.getSourceTypeCurrent().name,
                    number = tv.number,
                    child = tv.child,
                    timestamp = System.currentTimeMillis(),
                    playerType = tv.playerType,
                    block = tv.block,
                    script = tv.script,
                    selector = tv.selector,
                    started = tv.started,
                    finished = tv.finished
                )
                val currentSources = SP.getStableSources()
                // 检查 id、playerType、uris 和 videoIndex 是否完全相同
                val existingSource = currentSources.firstOrNull { it.id == newSource.id }
                if (existingSource != null &&
                    existingSource.playerType == newSource.playerType &&
                    existingSource.uris == newSource.uris &&
                    existingSource.videoIndex == newSource.videoIndex
                ) {
                    Log.d(TAG, "Skipping save stable source: ${newSource.title}, identical to existing (playerType=${newSource.playerType}, url=$currentUrl, videoIndex=${newSource.videoIndex})")
                    return@launch
                }
                // 保存新源，覆盖同 id 的旧源
                val updatedSources = (currentSources.filter { it.id != newSource.id } + newSource)
                    .sortedByDescending { it.timestamp }.take(200)
                SP.setStableSources(updatedSources)
                Log.d(TAG, "Saved stable source: ${newSource.title}, playerType=${newSource.playerType}, url=$currentUrl, videoIndex=${newSource.videoIndex}, uris=${newSource.uris}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save stable source: ${tvModel.tv.title}, error=${e.message}", e)
            }
        }
    }

    @OptIn(UnstableApi::class)
    fun ensurePlaying() {
        player?.run {
            if (!isPlaying && tvModel != null) {
                prepare()
                playWhenReady = true
            }
        }
    }

    @OptIn(UnstableApi::class)
    fun switchSource(tvModel: TVModel, showToast: Boolean = false) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSwitchSourceTime < switchSourceDebounce) {
            Log.d(TAG, "Debounced switchSource for ${tvModel.tv.title}")
            return
        }
        lastSwitchSourceTime = currentTime
        playbackStartTime = currentTime

        // 切换到下一条健康线路（自动跳过已探测失败的线路）
        val beforeIndex = tvModel.videoIndexValue
        tvModel.nextVideo()
        if (tvModel.videoIndexValue == beforeIndex && tvModel.tv.uris.size > 1) {
            // 所有线路都已失败：停止重试，避免无限换线循环
            Log.w(TAG, "All lines failed for ${tvModel.tv.title}, stop retrying")
            tvModel.setErrInfo(R.string.play_error.getString())
            return
        }
        tvModel.confirmVideoIndex()

        // 获取源数量和当前序列号
        val totalSources = tvModel.tv.uris.filter { it.isNotBlank() }.size
        val sourceIndex = tvModel.videoIndexValue + 1

        var toast: Toast? = null
        // 自动换线（失败重试）静默；手动换线才提示
        if (showToast) {
            toast = Toast.makeText(
                requireContext(),
                "线路 $sourceIndex / $totalSources",
                Toast.LENGTH_LONG
            )
            val textView = toast?.view?.findViewById<TextView>(android.R.id.message)
            textView?.textSize = 30f
            toast?.setGravity(Gravity.CENTER, 0, 0)
            toast?.show()
        }

        handler.removeCallbacks(checkPlaybackRunnable)
        handler.removeCallbacks(stableSourceCheckRunnable)
        if (toast != null) {
            handler.postDelayed({ toast?.cancel() }, 5000)
        }

        //Toast.makeText(requireContext(), R.string.switching_live_source, Toast.LENGTH_SHORT).show()
        this.tvModel = tvModel

        val actualUrl = tvModel.getVideoUrl()
        Log.d(TAG, "After switchSource: title=${tvModel.tv.title}, videoIndex=${tvModel.videoIndexValue}, url=$actualUrl, playerUrl=${player?.currentMediaItem?.localConfiguration?.uri}")
        val videoUrl = tvModel.getVideoUrl() ?: run {
            Log.w(TAG, "No valid URL for ${tvModel.tv.title}")
            tvModel.setErrInfo(R.string.play_error.getString())
            return
        }
        Log.d(TAG, "Switching source: ${tvModel.tv.title}, url: $videoUrl, videoIndex=${tvModel.videoIndexValue}")
        player?.run {
            val mediaItem = tvModel.getMediaItem()
            if (mediaItem == null) {
                Log.w(TAG, "No valid mediaItem for ${tvModel.tv.title}")
                tvModel.setErrInfo(R.string.play_error.getString())
                return
            }
            stop()
            clearMediaItems()
            val mediaSource = tvModel.getMediaSource()
            try {
                // 优化 HLS 配置
                val hlsMediaSource = if (mediaSource != null && videoUrl.endsWith(".m3u8")) {
                    HlsMediaSource.Factory(DefaultHttpDataSource.Factory())
                        .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(3)) // 重试 3 次
                        .createMediaSource(mediaItem)
                } else {
                    mediaSource
                }
                if (hlsMediaSource != null) {
                    setMediaSource(hlsMediaSource)
                } else {
                    setMediaItem(mediaItem)
                }
                prepare()
                playWhenReady = true
                Log.d(TAG, "Switched to source: ${tvModel.tv.title}, videoIndex=${tvModel.videoIndexValue}, url=$videoUrl")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to switch source for ${tvModel.tv.title}: ${e.message}", e)
                tvModel.setErrInfo(R.string.play_error.getString())
                // 自动切换到下一个源
                if (tvModel.tv.uris.size > tvModel.videoIndexValue + 1) {
                    tvModel.setVideoIndex(tvModel.videoIndexValue + 1)
                    tvModel.confirmVideoIndex()
                    switchSource(tvModel)
                    Log.d(TAG, "Retrying with next source: index=${tvModel.videoIndexValue}, url=${tvModel.getVideoUrl()}")
                }
            }
        } ?: Log.w(TAG, "Player is null, cannot switch source for ${tvModel.tv.title}")
        Log.d(TAG, "After switchSource: title=${tvModel.tv.title}, videoIndex=${tvModel.videoIndexValue}, url=${tvModel.getVideoUrl()}")
        // 重新调度定时任务
        handler.postDelayed(checkPlaybackRunnable, checkPlaybackInterval)
        handler.postDelayed(stableSourceCheckRunnable, stablePlaybackDuration)
        Log.d(TAG, "switchSource: Rescheduled checkPlaybackRunnable and stableSourceCheckRunnable")
    }

    @OptIn(UnstableApi::class)
    fun play(tvModel: TVModel) {
        val currentTime = System.currentTimeMillis()
        // 防抖只拦截"同一线路的重复播放"，连续切不同频道不拦截
        if (currentTime - lastSwitchSourceTime < switchSourceDebounce &&
            this.tvModel != null && this.tvModel?.getVideoUrl() == tvModel.getVideoUrl()
        ) {
            Log.d(TAG, "Debounced play for ${tvModel.tv.title}")
            return
        }
        lastSwitchSourceTime = currentTime
        this.tvModel = tvModel
        // 不再切台时探测全部线路（避免并发请求挤占网络）；
        // 线路健康由后台 probeAllLines（首线路）+ 播放失败动态标记维护
        val stableSource = SP.getStableSources().firstOrNull { it.id == tvModel.tv.id }
        if (stableSource != null) {
            tvModel.tv = tvModel.tv.copy(
                playerType = stableSource.playerType,
                videoIndex = stableSource.videoIndex
            )
            tvModel.setVideoIndex(stableSource.videoIndex)
            Log.d(TAG, "Applied stable source: ${tvModel.tv.title}, playerType=${tvModel.tv.playerType}, url=${tvModel.getVideoUrl()}, videoIndex=${tvModel.videoIndexValue}")
        } else {
            Log.d(TAG, "No stable source found for ${tvModel.tv.title}, using default uris=${tvModel.tv.uris}, videoIndex=${tvModel.videoIndexValue}")
        }
        Log.d(TAG, "Playing tvModel: ${tvModel.tv.title}, playerType: ${tvModel.tv.playerType}, uris: ${tvModel.tv.uris.size}")

        // 选择可用线路：跳过已探测失败的线路（稳定源线路优先保留）
        if (tvModel.tv.playerType != PlayerType.WEBVIEW && tvModel.tv.uris.size > 1) {
            val currentIdx = tvModel.videoIndexValue
            val stableUrl = SP.getStableSources().firstOrNull { it.id == tvModel.tv.id }?.uris?.firstOrNull()
            // 综合评分选线：延迟桶优先（快线>中速>慢速），同桶内清晰度高的优先，稳定源线路加权
            val betterIdx = tvModel.tv.uris.withIndex()
                .filter { !LineHealth.isDead(it.value) }
                .minByOrNull { (_, url) ->
                    val bucket = LineHealth.latency(url)?.let { l ->
                        when {
                            l < 400 -> 0
                            l < 2_000 -> 1
                            else -> 2
                        }
                    } ?: 1
                    bucket * 100_000 +
                            (if (url == stableUrl) 0 else 10_000) -
                            SourceQuality.scoreWithResolution(url, SP.getResolutionCache(url))
                }?.index ?: currentIdx
            if (betterIdx != null && betterIdx != currentIdx) {
                tvModel.setVideoIndex(betterIdx)
                Log.d(TAG, "Selected healthy line ${betterIdx + 1}/${tvModel.tv.uris.size} for ${tvModel.tv.title}")
            }
        }

        if (tvModel.tv.playerType == PlayerType.WEBVIEW) {
            player?.release()
            player = null
            binding.playerView.player = null
            binding.playerView.visibility = View.GONE
            binding.playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            binding.webView.visibility = View.VISIBLE
            val app = YourTVApplication.getInstance()
            binding.webView.layoutParams = FrameLayout.LayoutParams(
                app.videoWidthPx(),
                app.videoHeightPx()
            ).apply {
                gravity = Gravity.CENTER
            }
            binding.webView.requestLayout()
            binding.webView.post {
                Log.d(TAG, "web_view actual size: width=${binding.webView.width}, height=${binding.webView.height}")
            }
            binding.webView.bringToFront()
            (activity as? MainActivity)?.updateFullScreenMode(SP.fullScreenMode)
            try {
                val webFragment = com.horsenma.mytv1.WebFragment()
                if (isAdded && !isDetached && !childFragmentManager.isStateSaved) {
                    childFragmentManager.beginTransaction()
                        .replace(R.id.web_view, webFragment)
                        .commitNow()
                    Log.d(TAG, "WebFragment loaded for ${tvModel.tv.title}")
                } else {
                    Log.w(TAG, "Skipped WebFragment loading: isAdded=$isAdded, isDetached=$isDetached, isStateSaved=${childFragmentManager.isStateSaved}")
                    tvModel.setErrInfo(R.string.play_error.getString())
                    binding.webView.visibility = View.GONE
                    binding.playerView.visibility = View.VISIBLE
                    binding.playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                    binding.playerView.bringToFront()
                    binding.playerView.requestFocus()
                    binding.playerView.requestLayout()
                    updatePlayer()
                    return
                }

                webFragment.setCallback(object : WebFragmentCallback {
                    override fun onPlaybackStarted() {
                        playbackStartTime = System.currentTimeMillis()
                        bufferingCount = 0
                        tvModel.retryTimes = 0
                        lastStopTime = 0L
                        Log.d(TAG, "WebView playback started for ${tvModel.tv.title}")
                    }
                    override fun onPlaybackStopped() {
                        isStable = false
                        playbackStartTime = 0L
                        lastStopTime = System.currentTimeMillis()
                        Log.d(TAG, "WebView playback stopped for ${tvModel.tv.title}")
                    }
                    override fun onPlaybackError(error: String) {
                        isStable = false
                        playbackStartTime = 0L
                        lastStopTime = System.currentTimeMillis()
                        tvModel.setErrInfo(error)
                        Log.e(TAG, "WebView playback error for ${tvModel.tv.title}: $error")
                    }
                })

                webFragment.viewLifecycleOwnerLiveData.observe(viewLifecycleOwner) { owner ->
                    if (owner != null) {
                        webFragment.play(
                            com.horsenma.mytv1.models.TVModel(
                                com.horsenma.mytv1.data.TV(
                                    id = tvModel.tv.id,
                                    title = tvModel.tv.title,
                                    name = tvModel.tv.name,
                                    uris = tvModel.tv.uris,
                                    group = tvModel.tv.group,
                                    logo = tvModel.tv.logo,
                                    block = tvModel.tv.block ?: emptyList(),
                                    script = tvModel.tv.script,
                                    selector = tvModel.tv.selector,
                                    started = tvModel.tv.started,
                                    finished = tvModel.tv.finished,
                                    index = tvModel.videoIndexValue
                                )
                            )
                        )
                        Log.d(TAG, "WebFragment playing: ${tvModel.tv.title}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load WebFragment: ${e.message}")
                tvModel.setErrInfo(R.string.play_error.getString())
                binding.webView.visibility = View.GONE
                binding.playerView.visibility = View.VISIBLE
                binding.playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                binding.playerView.bringToFront()
                binding.playerView.requestFocus()
                binding.playerView.requestLayout()
                updatePlayer()
            }
            binding.webView.requestFocus()
            binding.playerView.isFocusable = false
            binding.playerView.setOnTouchListener(null)
        } else {
            // IPTV 播放逻辑。 移除 WebFragment
            if (isAdded && !isDetached && childFragmentManager.isStateSaved.not()) {
                childFragmentManager.findFragmentById(R.id.web_view)?.let { webFragment ->
                    if (webFragment is com.horsenma.mytv1.WebFragment) {
                        try {
                            childFragmentManager.beginTransaction()
                                .remove(webFragment)
                                .commit()
                            Log.d(TAG, "Removed WebFragment for ${tvModel.tv.title}")
                        } catch (e: IllegalStateException) {
                            Log.e(TAG, "Failed to remove WebFragment: ${e.message}", e)
                        }
                    }
                }
            } else {
                Log.w(TAG, "Skipped WebFragment removal: isAdded=$isAdded, isDetached=$isDetached, isStateSaved=${childFragmentManager.isStateSaved}")
            }
            binding.playerView.visibility = View.VISIBLE
            binding.webView.visibility = View.GONE
            binding.playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            binding.playerView.bringToFront()
            updatePlayerViewLayout()
            binding.playerView.requestFocus()
            binding.playerView.requestLayout()

            Log.d(TAG, "Playing IPTV: ${tvModel.tv.title}, uris: ${tvModel.tv.uris.size}")

            // 秒切路径：目标频道已在备用播放器就绪 → 无缝接管，无黑屏等待
            val targetUrl = tvModel.getVideoUrl()
            val seamlessSwitched = standbyPlayer != null && standbyReady && standbyChannelId == tvModel.tv.id
            if (seamlessSwitched) {
                // 同步频道线路索引到备用播放器已预准备的线路
                val standbyUrl = standbyTargetUrl
                if (standbyUrl != null) {
                    val idx = tvModel.tv.uris.indexOfFirst { it == standbyUrl }
                    if (idx >= 0) {
                        tvModel.setVideoIndex(idx)
                        tvModel.confirmVideoIndex()
                    }
                }
                val old = player
                player = standbyPlayer
                standbyPlayer = null
                standbyTargetUrl = null
                standbyChannelId = -1
                standbyReady = false
                binding.playerView.player = player
                binding.standbyView.player = null
                player?.play()
                old?.stop()
                old?.release()
                Log.d(TAG, "Seamless switch to standby: ${tvModel.tv.title}, url=$targetUrl")
            } else {
                releaseStandby()
            }

            if (!seamlessSwitched) {
                // 确保 player 存在且绑定到 PlayerView
                if (player == null) {
                    player = ExoPlayer.Builder(requireContext())
                        .setLoadControl(createFastLoadControl())
                        .build().apply {
                        addListener(object : Player.Listener {
                            override fun onVideoSizeChanged(videoSize: VideoSize) {
                                Log.d(TAG, "Video size changed: ${videoSize.width}x${videoSize.height}")
                                updatePlayerViewLayout()
                            }
                            override fun onPlaybackStateChanged(playbackState: Int) {
                                if (playbackState == Player.STATE_READY) {
                                    Log.d(TAG, "${tvModel.tv.title} is playing")
                                    prepareStandbyForNextChannel()
                                }
                            }
                            override fun onPlayerError(error: PlaybackException) {
                                Log.e(TAG, "Player error for ${tvModel.tv.title}: ${error.message}")
                                tvModel.setErrInfo(R.string.play_error.getString())
                            }
                        })
                    }
                    Log.d(TAG, "Created new ExoPlayer for ${tvModel.tv.title}")
                }
                // 绑定 PlayerView
                binding.playerView.player = player
                // 如果暂停状态，准备恢复
                if (player?.isPlaying == false) {
                    //player?.stop()
                    player?.prepare()
                    player?.playWhenReady = true
                    Log.d(TAG, "Resumed existing ExoPlayer for ${tvModel.tv.title}")
                }

                player?.run {
                    val videoUrl = tvModel.tv.uris.getOrNull(tvModel.videoIndexValue) ?: run {
                        Log.w(TAG, "No valid URL in uris for ${tvModel.tv.title}")
                        tvModel.setErrInfo(R.string.play_error.getString())
                        return
                    }
                    if (videoUrl == null) {
                        Log.w(TAG, "getVideoUrl failed for ${tvModel.tv.title}")
                        tvModel.setErrInfo(R.string.play_error.getString())
                        return
                    }

                    val mediaItem = tvModel.getMediaItem()
                    if (mediaItem == null) {
                        Log.w(TAG, "No valid mediaItem for ${tvModel.tv.title}")
                        tvModel.setErrInfo(R.string.play_error.getString())
                        return
                    }
                    val mediaSource = tvModel.getMediaSource()
                    try {
                        if (mediaSource != null) {
                            setMediaSource(mediaSource)
                        } else {
                            setMediaItem(mediaItem)
                        }
                        prepare()
                        playWhenReady = true
                        Log.d(TAG, "IPTV playback started for ${tvModel.tv.title}")
                    } catch (e: Exception) {
                        Log.e(TAG, "IPTV playback failed for ${tvModel.tv.title}: ${e.message}")
                        tvModel.setErrInfo(R.string.play_error.getString())
                    }
                } ?: Log.w(TAG, "Player is null, cannot play ${tvModel.tv.title}")
            }
            binding.playerView.requestFocus()
            binding.webView.isFocusable = false
        }
    }

    @OptIn(UnstableApi::class)
    fun updateSource() {
        tvModel?.let { model ->
            player?.run {
                stop()
                clearMediaItems()
                play(model)
            }
        }
    }

    private fun isTouchScreenDevice(): Boolean {
        val context = context ?: return false
        val packageManager = context.packageManager
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
        val isTv = uiModeManager?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        val hasTouchScreen = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        return hasTouchScreen && !isTv
    }

    /** 释放备用播放器 */
    private fun releaseStandby() {
        standbyPlayer?.release()
        standbyPlayer = null
        standbyTargetUrl = null
        standbyChannelId = -1
        standbyReady = false
        binding.standbyView.player = null
    }

    /**
     * 当前频道播放就绪后，后台预准备"下一频道"（列表下一个 IPTV 频道）。
     * prepare 到 READY 后停在首帧（不 play 不持续拉流），切台时无缝接管。
     */
    private fun prepareStandbyForNextChannel() {
        if (!::viewModel.isInitialized) return
        // 电视等弱机不启用备用播放器（双解码器内存/带宽压力大），只保留触屏设备秒切体验
        if (!isTouchScreenDevice()) return
        // 全列表顺序取"当前频道的下一个"（与按键切台顺序一致，且无 getNext 的位置副作用）
        val current = viewModel.groupModel.getCurrent() ?: return
        val allModels = viewModel.listModel
        val currentIdx = allModels.indexOfFirst { it.tv.id == current.tv.id }
        if (currentIdx < 0 || currentIdx + 1 >= allModels.size) return
        val next = allModels[currentIdx + 1]
        if (standbyPlayer != null) {
            if (standbyChannelId == next.tv.id) return // 已预准备当前目标
            releaseStandby() // 频道漂移：目标变化，重建
        }
        if (next.tv.playerType != PlayerType.IPTV) return
        val url = next.getVideoUrl() ?: return
        if (url.isBlank() || !url.startsWith("http")) return

        standbyTargetUrl = url
        standbyChannelId = next.tv.id
        standbyReady = false
        standbyPlayer = ExoPlayer.Builder(requireContext())
            .setLoadControl(createFastLoadControl())
            .build().apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) {
                            standbyReady = true
                            Log.d(TAG, "Standby ready for next channel: $url")
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.d(TAG, "Standby prepare failed, discard: ${error.message}")
                        releaseStandby()
                    }
                })
            }
        binding.standbyView.player = standbyPlayer
        standbyPlayer?.setMediaItem(MediaItem.fromUri(url))
        standbyPlayer?.prepare()
        Log.d(TAG, "Preparing standby for next channel: ${next.tv.title}, url=$url")
    }

    /** 低缓冲加载策略：直播秒开（minBuffer 500ms），避免默认 2.5s 缓冲等待 */
    @OptIn(UnstableApi::class)
    private fun createFastLoadControl(): DefaultLoadControl {
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                500,
                2_000,
                300,
                500
            )
            .build()
    }

    /**
     * 预热指定 URL 的连接（OkHttp 连接池复用），切台时省去 TCP/TLS 握手。
     * 读取少量字节后关闭，连接保留在共享连接池中。
     */
    fun prewarm(url: String?) {
        if (url.isNullOrBlank() || !(url.startsWith("http://") || url.startsWith("https://"))) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val request = okhttp3.Request.Builder().url(url)
                    .header("Range", "bytes=0-65535")
                    .build()
                HttpClient.okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        // 读取部分内容确保连接完成，随后关闭（连接回池复用）
                        response.bodyAlias()?.byteStream()?.use { it.read(ByteArray(4096)) }
                        Log.d(TAG, "Prewarm done: $url")
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Prewarm failed (ignored): ${e.message}")
            }
        }
    }

    @OptIn(UnstableApi::class)
    class PlayerMediaCodecSelector : MediaCodecSelector {
        override fun getDecoderInfos(
            mimeType: String,
            requiresSecureDecoder: Boolean,
            requiresTunnelingDecoder: Boolean
        ): MutableList<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> {
            val infos = MediaCodecUtil.getDecoderInfos(
                mimeType,
                requiresSecureDecoder,
                requiresTunnelingDecoder
            )
            // 在 API 23 上优先选择软件解码器，确保兼容性
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.M) {
                val softwareCodecs = infos.filter { !it.hardwareAccelerated }
                if (softwareCodecs.isNotEmpty()) {
                    Log.d(TAG, "API 23 detected, using software codecs for $mimeType")
                    return softwareCodecs.toMutableList()
                }
            }
            if (SP.softDecode) {
                val softwareCodecs = infos.filter { !it.hardwareAccelerated }
                if (softwareCodecs.isNotEmpty()) {
                    return softwareCodecs.toMutableList()
                }
            } else if (mimeType.startsWith("audio/")) {
                val softwareCodecs = infos.filter { !it.hardwareAccelerated }
                if (softwareCodecs.isNotEmpty()) {
                    return softwareCodecs.toMutableList()
                }
            }
            if (mimeType == MimeTypes.VIDEO_H265 && !requiresSecureDecoder && !requiresTunnelingDecoder) {
                if (infos.isNotEmpty()) {
                    val infosNew = infos.find { it.name == "c2.android.hevc.decoder" }
                        ?.let { mutableListOf(it) }
                    if (infosNew != null) {
                        return infosNew
                    }
                }
            }
            return infos
        }
    }

    @OptIn(UnstableApi::class)
    fun getCurrentResolution(): String? {
        return if (tvModel?.tv?.playerType == PlayerType.IPTV) {
            player?.videoSize?.let { videoSize ->
                if (videoSize.width > 0 && videoSize.height > 0) "${videoSize.width}x${videoSize.height}" else null
            }
        } else {
            null // WebView 源无分辨率信息
        }
    }

    fun showVolume(visibility: Int) {
        binding.icon.visibility = visibility
        binding.volume.visibility = visibility
        hideVolume()
    }

    fun setVolumeMax(volume: Int) {
        binding.volume.max = volume
    }

    fun setVolume(progress: Int, volume: Boolean = false) {
        val context = requireContext()
        binding.volume.progress = progress
        binding.icon.setImageDrawable(
            ContextCompat.getDrawable(
                context,
                if (volume) {
                    if (progress > 0) R.drawable.volume_up_24px else R.drawable.volume_off_24px
                } else {
                    R.drawable.light_mode_24px
                }
            )
        )
    }

    fun hideVolume() {
        handler.removeCallbacks(hideVolumeRunnable)
        handler.postDelayed(hideVolumeRunnable, delayHideVolume)
    }

    fun hideVolumeNow() {
        handler.removeCallbacks(hideVolumeRunnable)
        handler.postDelayed(hideVolumeRunnable, 0)
    }

    private val hideVolumeRunnable = Runnable {
        binding.icon.visibility = View.GONE
        binding.volume.visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        if (player?.isPlaying == false) {
            player?.prepare()
            player?.play()
        }
        // 确保定时器运行
        handler.removeCallbacks(checkPlaybackRunnable)
        handler.removeCallbacks(stableSourceCheckRunnable)
        handler.postDelayed(checkPlaybackRunnable, checkPlaybackInterval)
    }

    override fun onPause() {
        super.onPause()
        if (tvModel?.tv?.playerType == PlayerType.WEBVIEW) {
            Log.d(TAG, "Skipping pause for WEBVIEW")
            return
        }
        if (!SP.enableScreenOffAudio && player != null) {
            player?.pause()
            Log.d(TAG, "Paused player due to SP.enableScreenOffAudio=false")
        }
        lastPauseTime = System.currentTimeMillis()
    }

    // 添加广播接收器
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                if (!SP.enableScreenOffAudio && player != null) {
                    player?.pause()
                    Log.d(TAG, "Paused player on SCREEN_OFF in ${if (isInPictureInPictureMode) "PiP" else "Full-Screen"} mode")
                }
            } else if (intent.action == Intent.ACTION_SCREEN_ON) {
                if (!SP.enableScreenOffAudio && player != null) {
                    player?.playWhenReady = true
                    Log.d(TAG, "Resumed player on SCREEN_ON in ${if (isInPictureInPictureMode) "PiP" else "Full-Screen"} mode")
                }
            }
        }
    }

    // 在 onCreate 中注册
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        try {
            requireActivity().registerReceiver(screenReceiver, filter)
            Log.d(TAG, "Screen broadcast receiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register screen broadcast receiver: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        releaseStandby()
        requireActivity().unregisterReceiver(screenReceiver)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        handler.removeCallbacks(checkPlaybackRunnable)
        handler.removeCallbacks(stableSourceCheckRunnable)
    }

    companion object {
        private const val TAG = "PlayerFragment"
    }
}
