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
import com.horsenma.yourtv.models.ChannelClassifier
import androidx.media3.ui.PlayerView
import com.horsenma.yourtv.data.StableSource
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
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

    /** Reset the idle window whenever the user is operating the TV UI. */
    fun markUserInteraction() {
        lastUserInteractionTime = System.currentTimeMillis()
        idleProbeJob?.cancel()
        if (::viewModel.isInitialized) viewModel.cancelLineProbes()
    }

    private fun maybeProbeIdleCandidate(now: Long) {
        if (!::viewModel.isInitialized || idleProbeJob?.isActive == true) return
        if (now - lastIdleProbeTime < idleProbeIntervalMs) return
        if (now - lastUserInteractionTime < probeInteractionGraceMs) return
        if ((activity as? MainActivity)?.hasBlockingOverlay() == true) return

        val model = tvModel ?: return
        val activePlayer = player ?: return
        if (model.tv.playerType != PlayerType.IPTV ||
            activePlayer.isPlaying != true ||
            activePlayer.playbackState != Player.STATE_READY ||
            activePlayer.playWhenReady != true ||
            activePlayer.bufferedPosition - activePlayer.currentPosition < minBufferBeforeProbeMs ||
            now - lastBufferingTime < probeInteractionGraceMs ||
            now - lastPlaybackDisruptionTime < probeInteractionGraceMs
        ) return

        val currentUrl = model.getVideoUrl()
        val candidate = model.tv.uris.firstOrNull { url ->
            url.isNotBlank() && url != currentUrl &&
                !LineHealth.isDead(url) && LineHealth.shouldProbe(url)
        } ?: return

        lastIdleProbeTime = now
        val headers = model.tv.uriHeaders[candidate] ?: model.tv.headers.orEmpty()
        idleProbeJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = viewModel.probeLine(candidate, headers)
                Log.d(TAG, "Idle candidate probe: url=$candidate, reachable=${result?.reachable}, latency=${result?.latencyMs}")
            } finally {
                idleProbeJob = null
            }
        }
    }
    private val stablePlaybackDuration = 30_000L
    private var isStable = false
    private var _binding: PlayerBinding? = null
    private val binding get() = _binding!!
    internal var player: ExoPlayer? = null
    internal var tvModel: TVModel? = null
    private val aspectRatio = 16f / 9f
    internal var isInPictureInPictureMode = false
    private val handler = Handler(Looper.myLooper()!!)

    private fun stableSourceFor(tvModel: TVModel): StableSource? {
        val key = ChannelClassifier.mergeKey(tvModel.tv.title, tvModel.tv.group)
        return SP.getStableSources().firstOrNull {
            ChannelClassifier.mergeKey(it.title, it.group) == key
        }
    }

    private fun stableUrl(source: StableSource?): String? {
        if (source == null) return null
        return source.uris.getOrNull(source.videoIndex) ?: source.uris.firstOrNull()
    }
    private val delayHideVolume = 2 * 1000L
    // 新增：缓冲检测变量
    // v3.3.0 校准：公开直播流轻微抖动常见，10 秒 5 次/8 秒累计即换线过于敏感，
    // 配合 500ms 缓冲会让"进频道 2-3 秒一换"（用户反馈"几秒后卡住像在切源"）。
    // 放宽为 15 秒 8 次事件 / 10 秒连续缓冲，并给单频道会话内换线上限。
    private val bufferingThreshold = 8
    private val bufferingWindowMs = 15_000L
    private val bufferingDurationThreshold = 10_000L
    private val maxAutoSwitchPerChannel = 5
    private var autoSwitchCount = 0
    private var autoSwitchChannelId = -1
    /** 本次播放尝试是否已出画（用于区分"起播缓冲中"与"播放中卡停"，v3.3.0） */
    private var attemptPlayed = false
    private val switchCooldown = 8_000L
    private val stablePlaybackThreshold = 10_000L
    private var bufferingStartTime = 0L
    private var bufferingCount = 0
    private var lastSwitchTime = 0L
    private var playbackStartTime = 0L
    /** 最近一次播放/换线请求时间：首帧看门狗以此计算"多久没出画" */
    private var playRequestTime = 0L
    private val bufferingTimestamps = mutableListOf<Long>()
    private var lastBufferingTime = 0L
    private var lastIdleProbeTime = 0L
    private var lastUserInteractionTime = 0L
    private var idleProbeJob: Job? = null
    private val idleProbeIntervalMs = 60_000L
    private val minBufferBeforeProbeMs = 8_000L
    private val probeInteractionGraceMs = 5_000L
    private var lastPlaybackDisruptionTime = 0L
    private var isSourceButtonVisible = false
    private var lastSwitchSourceTime = 0L
    private val switchSourceDebounce = 2_000L
    private var lastFallbackTime = 0L
    private val fallbackCooldown = 10_000L
    // 新增：播放停止检测变量
    private var lastStopTime = 0L
    private val stopDurationThreshold = 2_500L
    private val retryCooldown = 8_000L
    // 会话内是否成功出过画面：从未出画时（启动稳定源失效等）跳过换线冷却与
    // 自动换源开关，立即换下一条线路，避免"首帧黑屏 30 秒"
    private var hasPlayedSuccessfully = false
    // 播放健康轮询：2s 一查（原 15s）。只做 isPlaying/state 检查，开销可忽略；
    // 缩短轮询后停播检测（2.5s 阈值）与首帧看门狗都能及时触发，不再让用户干等。
    private val checkPlaybackInterval = 2_000L

    /** 首帧看门狗：播放请求发出后该时长内未出画（持续 BUFFERING 且无错误事件）即换下一条线路 */
    private val firstFrameTimeoutMs = 8_000L
    // 定义保存间隔（例如 5 分钟，防止频繁保存）
    private var lastPauseTime = 0L
    /** 稳定源保存节流：健康轮询 2s 一查后，同一频道 30s 内只保存一次 */
    private var lastStableSaveTime = 0L
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

    /**
     * 播放器池：主播放器（当前可见）+ 备用播放器（预加载下一频道）。
     * 两个实例启动时创建、切台时角色交换复用，绝不反复创建/释放；
     * 备用播放器在电视上也启用（设置可关），预加载到 READY 停在首帧，
     * 切台时无缝接管，实现秒切。
     */
    @OptIn(UnstableApi::class)
    fun updatePlayer() {
        if (context == null) {
            Log.e(TAG, "context == null")
            return
        }
        ensurePlayerPool()
    }

    /** 软解切换等播放器设置变化：释放并重建播放器。 */
    @OptIn(UnstableApi::class)
    fun rebuildPlayers() {
        releaseAllPlayers()
        ensurePlayerPool()
    }

    @OptIn(UnstableApi::class)
    private fun ensurePlayerPool() {
        val ctx = requireContext()
        if (player == null) {
            player = buildMainPlayer(ctx)
            binding.playerView.player = player
            player?.playWhenReady = true
            Log.d(TAG, "Main player created")
        }
        // 定时任务随播放器常驻
        handler.removeCallbacks(checkPlaybackRunnable)
        handler.removeCallbacks(stableSourceCheckRunnable)
        handler.postDelayed(checkPlaybackRunnable, checkPlaybackInterval)
        handler.postDelayed(stableSourceCheckRunnable, stablePlaybackDuration)
    }

    @OptIn(UnstableApi::class)
    private fun buildMainPlayer(ctx: android.content.Context): ExoPlayer {
        val renderersFactory = DefaultRenderersFactory(ctx)
        val playerMediaCodecSelector = PlayerMediaCodecSelector()
        renderersFactory.setMediaCodecSelector(playerMediaCodecSelector)
        renderersFactory.setExtensionRendererMode(
            if (SP.softDecode) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
        )
        val exo = ExoPlayer.Builder(ctx)
            .setRenderersFactory(renderersFactory)
            .setLoadControl(createFastLoadControl())
            .build()
        exo.repeatMode = REPEAT_MODE_ALL
        exo.addListener(createMainPlayerListener(exo))
        return exo
    }

    /** 主播放器监听：出画状态/缓冲监控/自动换线/分辨率缓存/布局刷新 */
    @OptIn(UnstableApi::class)
    private fun createMainPlayerListener(exo: ExoPlayer): Player.Listener {
        return object : Player.Listener {
            private val self = exo

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (self !== player) return
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
                if (self !== player) return
                if (tvModel == null) {
                    Log.e(TAG, "tvModel == null")
                    return
                }

                val tv = tvModel!!
                if (isPlaying) {
                    val now = System.currentTimeMillis()
                    val firstPlaybackForAttempt = !attemptPlayed
                    hasPlayedSuccessfully = true
                    attemptPlayed = true
                    // Real playback on this device/network is the strongest
                    // health signal and must recover a line from old failures.
                    tv.getVideoUrl()?.let { url ->
                        LineHealth.markPlaybackSuccess(
                            url,
                            if (firstPlaybackForAttempt) (now - playRequestTime).coerceAtLeast(0L) else -1L,
                        )
                    }
                    tv.confirmSourceType()
                    tv.confirmVideoIndex()
                    tv.setErrInfo("")
                    tv.retryTimes = 0
                    bufferingCount = 0
                    bufferingStartTime = 0L
                    bufferingTimestamps.clear()
                    lastBufferingTime = 0L
                    playbackStartTime = now
                    playbackCallback?.onPlaybackStarted()
                    lastStopTime = 0L // 重置停止时间
                    Log.d(TAG, "${tv.tv.title} is playing")
                } else {
                    idleProbeJob?.cancel()
                    if (::viewModel.isInitialized) viewModel.cancelLineProbes()
                    lastPlaybackDisruptionTime = System.currentTimeMillis()
                    isStable = false
                    playbackStartTime = 0L // 重置计时
                    lastStopTime = System.currentTimeMillis() // 记录停止时间
                    Log.i(TAG, "${tv.tv.title} 播放停止")
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (self !== player) return
                val currentTime = System.currentTimeMillis()
                if (state == Player.STATE_BUFFERING) {
                    lastPlaybackDisruptionTime = currentTime
                    idleProbeJob?.cancel()
                    if (::viewModel.isInitialized) viewModel.cancelLineProbes()
                }
                if (!SP.autoSwitchSource) return
                if (tvModel == null || player == null) {
                    return
                }

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
                    // 统计最近缓冲窗口内的缓冲次数
                    bufferingCount = bufferingTimestamps.count { it >= currentTime - bufferingWindowMs }
                    val bufferingDuration = currentTime - bufferingStartTime

                    // 清理过旧的时间戳
                    bufferingTimestamps.removeAll { it < currentTime - bufferingWindowMs }

                    // 检查是否需要切换源
                    if (autoSwitchCount < maxAutoSwitchPerChannel &&
                        ((bufferingCount >= bufferingThreshold && currentTime - lastSwitchTime >= switchCooldown) ||
                        (bufferingDuration >= bufferingDurationThreshold && currentTime - lastSwitchTime >= switchCooldown))) {
                        if (tvModel!!.retryTimes < tvModel!!.retryMaxTimes && player!!.currentPosition > 0) {
                            Log.i(TAG, "Non-smooth playback detected: bufferingCount=$bufferingCount, duration=$bufferingDuration")
                            tvModel?.getVideoUrl()?.let { LineHealth.markPlaybackFailure(it) }
                            (activity as MainActivity).sourceUp(false)
                            lastSwitchTime = currentTime
                            autoSwitchCount++
                            autoSwitchChannelId = tvModel!!.tv.id
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
                if (self !== player) return
                if (reason == DISCONTINUITY_REASON_AUTO_TRANSITION) {
                    (activity as MainActivity).onPlayEnd()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (self !== player) return
                Log.w(TAG, "Player error: ${error.errorCode}, message=${error.message}")
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
                // Playback failure gets a stronger cooldown than a background
                // probe failure, but a later success can recover immediately.
                if (tvModel?.tv?.playerType != PlayerType.WEBVIEW) {
                    tvModel?.getVideoUrl()?.let { LineHealth.markPlaybackFailure(it) }
                }
                lastStopTime = System.currentTimeMillis()
                Log.w(TAG, "Marking for retry: lastStopTime=$lastStopTime, cooldownRemaining=${if (System.currentTimeMillis() - lastSwitchTime < retryCooldown) retryCooldown - (System.currentTimeMillis() - lastSwitchTime) else 0}")
                // 从未成功出画：忽略 30s 冷却立即换线（启动期稳定源失效场景）
                if (!attemptPlayed || System.currentTimeMillis() - lastSwitchTime >= retryCooldown) {
                    Log.w(TAG, "${tvModel?.tv?.title} error, retrying immediately")
                    tvModel?.let { switchSource(it) }
                    lastSwitchTime = System.currentTimeMillis()
                    lastStopTime = 0L
                    return
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
                    } else if (tvModel!!.hasNextHealthyVideo()) {
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
                        // 不要把播放失败的频道静默替换成下一个频道：这会造成
                        // 用户选择 CCTV1 却不断跳到 CCTV2/3。保留频道焦点，
                        // 让用户手动换线或重新播放，并显示明确错误。
                        tvModel!!.setErrInfo(R.string.play_error.getString())
                        Log.w(TAG, "First use: all lines failed for ${tvModel!!.tv.title}; keeping channel selected")
                        return
                    }
                }

                if (!SP.autoSwitchSource && hasPlayedSuccessfully) {
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
                    if (tv.hasNextHealthyVideo()) {
                        tv.nextVideo()
                        tv.setReady(true)
                        tv.retryTimes = 0
                        (activity as MainActivity).sourceUp(false)
                    } else {
                        // 播放失败只在当前频道内耗尽线路，不跨频道漂移。
                        // 稳定源只能用于启动恢复，不能覆盖用户当前的频道选择。
                        tv.setErrInfo(R.string.play_error.getString())
                        Log.w(TAG, "All lines failed for ${tv.tv.title}; keeping channel selected")
                    }
                }
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun updatePlayerViewLayout() {
        applyVideoLayout(binding.playerView)
    }

    /**
     * 主/备播放视图共用同一套布局规则：
     * - resizeMode=FIT 等比缩放（4:3/21:9 源留黑边不变形）
     * - 全屏=铺满屏幕，非全屏=16:9 应显区域，均居中
     * 根布局已改为 FrameLayout，FrameLayout.LayoutParams 原生生效，
     * 不再有替换 ConstraintLayout.LayoutParams 导致约束丢失的隐患。
     */
    @OptIn(UnstableApi::class)
    private fun applyVideoLayout(playerView: androidx.media3.ui.PlayerView) {
        val app = YourTVApplication.getInstance()
        val isFullScreen = SP.fullScreenMode

        // 画面比例（G7）：fit=跟随内容；16_9/4_3=按目标比例留边（FIT + 自定义宽高比）；
        // zoom=铺满裁剪。主/备视图同步，比例表现一致。
        val aspectFrame = playerView.findViewById<androidx.media3.ui.AspectRatioFrameLayout>(
            androidx.media3.ui.R.id.exo_content_frame
        )
        when (SP.aspectRatio) {
            "16_9" -> {
                playerView.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                aspectFrame?.setAspectRatio(16f / 9f)
            }
            "4_3" -> {
                playerView.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                aspectFrame?.setAspectRatio(4f / 3f)
            }
            "zoom" -> {
                playerView.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                aspectFrame?.setAspectRatio(0f)
            }
            else -> {
                playerView.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                aspectFrame?.setAspectRatio(0f)
            }
        }

        val layoutParams = FrameLayout.LayoutParams(
            if (isFullScreen) ViewGroup.LayoutParams.MATCH_PARENT else app.videoWidthPx(),
            if (isFullScreen) ViewGroup.LayoutParams.MATCH_PARENT else app.videoHeightPx()
        ).apply {
            gravity = Gravity.CENTER // 确保居中
        }
        playerView.layoutParams = layoutParams

        playerView.requestLayout()
        playerView.post {
            Log.d(TAG, "Updated player layout: fullScreen=$isFullScreen, width=${layoutParams.width}, height=${layoutParams.height}, gravity=${layoutParams.gravity}")
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
            if (tvModel!!.retryTimes >= tvModel!!.retryMaxTimes &&
                !tvModel!!.errInfo.value.isNullOrBlank()
            ) {
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
            // 首帧看门狗（IPTV）：播放请求后持续 BUFFERING 超过阈值且无错误事件
            // （ExoPlayer 不会为"永远缓冲"发 onPlayerError），直接标记坏线换下一条，
            // 避免黑屏/转圈干等。换线节奏由 switchSourceDebounce(2s) 与
            // nextVideo 的"全部线路试完才停止"语义兜底，不会在坏线间死循环。
            if (!isPlaying &&
                tvModel!!.tv.playerType == PlayerType.IPTV &&
                player?.playbackState == Player.STATE_BUFFERING &&
                !attemptPlayed &&
                currentTime - playRequestTime >= firstFrameTimeoutMs &&
                tvModel!!.retryTimes < tvModel!!.retryMaxTimes
            ) {
                Log.w(TAG, "${tvModel!!.tv.title} buffering ${(currentTime - playRequestTime) / 1000}s without first frame, switching line")
                tvModel?.getVideoUrl()?.let { LineHealth.markPlaybackFailure(it) }
                switchSource(tvModel!!)
                lastSwitchTime = currentTime
                lastStopTime = 0L
            }
            if (!isPlaying && lastStopTime > 0 && stopDuration >= stopDurationThreshold &&
                cooldownRemaining == 0L && attemptPlayed
            ) {
                if (tvModel?.tv?.playerType == PlayerType.WEBVIEW) {
                    // 网页源解析/加载慢（可达数十秒），停播检测不适用：
                    // 交给 WebFragment 内部超时重载，不在这里自动换线/弹错
                    Log.d(TAG, "WebView channel slow to start, skipping stop-based auto-switch")
                    lastStopTime = 0L
                } else {
                    Log.w(TAG, "${tvModel!!.tv.title} stopped after playing for ${stopDurationThreshold / 1000}s, retrying")
                    // 停播超时：标记当前线路不可用
                    tvModel?.getVideoUrl()?.let { LineHealth.markPlaybackFailure(it) }
                    switchSource(tvModel!!)
                    lastSwitchTime = currentTime
                    lastStopTime = 0L
                }
            } else if (isPlaying && stopDuration == 0L && cooldownRemaining == 0L) {
                // Check for stable source saving
                if (currentTime - lastStableSaveTime >= stablePlaybackDuration && // 30s 节流
                    currentTime - playbackStartTime >= stablePlaybackDuration && // 播放持续 30 秒
                    bufferingCount == 0 && tvModel!!.retryTimes == 0) {
                    isStable = true
                    saveStableSource(tvModel!!)
                    lastStableSaveTime = currentTime
                    Log.d(TAG, "Stable source saved: ${tvModel!!.tv.title}, playerType=${tvModel!!.tv.playerType}, isPlaying=$isPlaying")
                }
                maybeProbeIdleCandidate(currentTime)
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
                    // Only the selected URL is persisted, so its index inside
                    // this compact record is always zero. Keeping the old
                    // aggregate index made a refreshed list select a random URL.
                    videoIndex = 0,
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
                val channelKey = ChannelClassifier.mergeKey(newSource.title, newSource.group)
                // 频道列表会在聚合后重新编号，不能只用 TV.id 判断稳定源归属。
                val existingSource = currentSources.firstOrNull {
                    it.id == newSource.id || ChannelClassifier.mergeKey(it.title, it.group) == channelKey
                }
                if (existingSource != null &&
                    existingSource.playerType == newSource.playerType &&
                    existingSource.uris == newSource.uris &&
                    existingSource.videoIndex == newSource.videoIndex
                ) {
                    Log.d(TAG, "Skipping save stable source: ${newSource.title}, identical to existing (playerType=${newSource.playerType}, url=$currentUrl, videoIndex=${newSource.videoIndex})")
                    return@launch
                }
                // 保存新源，覆盖同 id 的旧源
                val updatedSources = (currentSources.filter {
                    it.id != newSource.id && ChannelClassifier.mergeKey(it.title, it.group) != channelKey
                } + newSource)
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
                if (::viewModel.isInitialized) viewModel.setPlaybackActive(true)
                prepare()
                playWhenReady = true
            }
        }
    }

    @OptIn(UnstableApi::class)
    fun switchSource(tvModel: TVModel, showToast: Boolean = false) {
        switchSourceInternal(tvModel, showToast, advance = true, force = false)
    }

    /** Play the exact line chosen in the line panel. */
    @OptIn(UnstableApi::class)
    fun selectSource(tvModel: TVModel, index: Int) {
        tvModel.setVideoIndex(index)
        tvModel.confirmVideoIndex()
        switchSourceInternal(tvModel, showToast = false, advance = false, force = true)
    }

    @OptIn(UnstableApi::class)
    private fun switchSourceInternal(
        tvModel: TVModel,
        showToast: Boolean,
        advance: Boolean,
        force: Boolean,
    ) {
        val currentTime = System.currentTimeMillis()
        if (!force && currentTime - lastSwitchSourceTime < switchSourceDebounce) {
            Log.d(TAG, "Debounced switchSource for ${tvModel.tv.title}")
            return
        }
        lastSwitchSourceTime = currentTime
        playbackStartTime = currentTime
        playRequestTime = currentTime
        attemptPlayed = false
        if (autoSwitchChannelId != tvModel.tv.id) {
            autoSwitchCount = 0
            autoSwitchChannelId = tvModel.tv.id
        }

        // Automatic/manual cycling advances to the next healthy line. A line
        // chosen from the panel must stay on the exact selected index.
        val moved = if (advance) tvModel.nextVideo() else tvModel.getVideoUrl() != null
        if (!moved) {
            Log.w(TAG, "No alternative line for ${tvModel.tv.title}, stop retrying")
            tvModel.setErrInfo(R.string.play_error.getString())
            tvModel.retryTimes = tvModel.retryMaxTimes
            handler.removeCallbacks(checkPlaybackRunnable)
            handler.removeCallbacks(stableSourceCheckRunnable)
            player?.stop()
            return
        }
        if (advance && !showToast) {
            tvModel.retryTimes++
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
        viewModel.setPlaybackActive(true)
        markUserInteraction()

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
                // Keep the OkHttp factory from TVModel so per-URI headers
                // (Referer/User-Agent/Cookie) survive source aggregation.
                if (mediaSource != null) {
                    setMediaSource(mediaSource)
                } else {
                    setMediaItem(mediaItem)
                }
                prepare()
                playWhenReady = true
                Log.d(TAG, "Switched to source: ${tvModel.tv.title}, videoIndex=${tvModel.videoIndexValue}, url=$videoUrl")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to switch source for ${tvModel.tv.title}: ${e.message}", e)
                LineHealth.markPlaybackFailure(videoUrl)
                tvModel.setErrInfo(R.string.play_error.getString())
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
        playRequestTime = currentTime
        attemptPlayed = false
        if (autoSwitchChannelId != tvModel.tv.id) {
            autoSwitchCount = 0
            autoSwitchChannelId = tvModel.tv.id
        }
        this.tvModel = tvModel
        viewModel.setPlaybackActive(true)
        markUserInteraction()
        // 最近观看：每次实际起播的频道都记录（去重顶置，跨重启保留）
        SP.addRecentChannel(tvModel.tv.id)
        // 线路健康主要来自真实播放；备用线路只在稳定缓冲且用户空闲时单条探测。
        val stableSource = stableSourceFor(tvModel)
        val rememberedUrl = stableUrl(stableSource)
        if (stableSource != null && rememberedUrl != null) {
            val mergedUris = SourceNetworkPolicy.diversify(
                listOf(rememberedUrl) + tvModel.tv.uris,
                limit = 8,
            )
            val rememberedIndex = mergedUris.indexOf(rememberedUrl).coerceAtLeast(0)
            tvModel.tv = tvModel.tv.copy(
                uris = mergedUris,
                playerType = stableSource.playerType,
                videoIndex = rememberedIndex,
                uriHeaders = tvModel.tv.uriHeaders + mapOf(rememberedUrl to stableSource.headers.orEmpty()),
            )
            tvModel.setVideoIndex(rememberedIndex)
            Log.d(TAG, "Applied stable source: ${tvModel.tv.title}, playerType=${tvModel.tv.playerType}, url=${tvModel.getVideoUrl()}, videoIndex=${tvModel.videoIndexValue}")
        } else {
            Log.d(TAG, "No stable source found for ${tvModel.tv.title}, using default uris=${tvModel.tv.uris}, videoIndex=${tvModel.videoIndexValue}")
        }
        Log.d(TAG, "Playing tvModel: ${tvModel.tv.title}, playerType: ${tvModel.tv.playerType}, uris: ${tvModel.tv.uris.size}")

        // 选择可用线路：跳过已探测失败的线路（稳定源线路优先保留）
        if (tvModel.tv.playerType != PlayerType.WEBVIEW && tvModel.tv.uris.size > 1) {
            val currentIdx = tvModel.videoIndexValue
            val stableUrl = rememberedUrl
            // Device health dominates heuristics. Unknown lines can no longer
            // outrank a confirmed working (but slightly slower) line.
            val betterIdx = tvModel.tv.uris.withIndex()
                .filter { !LineHealth.isDead(it.value) }
                .maxByOrNull { (index, url) ->
                    LineHealth.healthRank(url) * 1_000_000_000L +
                        (if (url == stableUrl) 100_000_000L else 0L) +
                        SourceNetworkPolicy.compatibilityScore(url) * 1_000_000L +
                        SourceQuality.scoreWithResolution(url, SP.getResolutionCache(url), tvModel.tv.title) * 10_000L -
                        (LineHealth.latency(url) ?: 30_000L).coerceAtMost(60_000L) -
                        index
                }?.index ?: currentIdx
            if (betterIdx != currentIdx) {
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

            // Reuse one player so current viewing owns the network and decoder resources.
            ensurePlayerPool()
            binding.playerView.player = player
            player?.run {
                val videoUrl = tvModel.tv.uris.getOrNull(tvModel.videoIndexValue) ?: run {
                    Log.w(TAG, "No valid URL in uris for ${tvModel.tv.title}")
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
                    stop()
                    clearMediaItems()
                    if (mediaSource != null) {
                        setMediaSource(mediaSource)
                    } else {
                        setMediaItem(mediaItem)
                    }
                    prepare()
                    playWhenReady = true
                    Log.d(TAG, "IPTV playback started for ${tvModel.tv.title}, url=$videoUrl")
                } catch (e: Exception) {
                    Log.e(TAG, "IPTV playback failed for ${tvModel.tv.title}: ${e.message}")
                    tvModel.setErrInfo(R.string.play_error.getString())
                }
            } ?: Log.w(TAG, "Player is null, cannot play ${tvModel.tv.title}")
            binding.playerView.requestFocus()
            binding.webView.isFocusable = false
        }
    }

    /** 画面比例设置变更后立即生效。 */
    @OptIn(UnstableApi::class)
    fun updateAspectRatio() {
        if (_binding == null) return
        applyVideoLayout(binding.playerView)
    }

    private fun isTouchScreenDevice(): Boolean {
        val context = context ?: return false
        val packageManager = context.packageManager
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
        val isTv = uiModeManager?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        val hasTouchScreen = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        return hasTouchScreen && !isTv
    }

    /** 释放播放器（软解切换重建/销毁时调用）。 */
    private fun releaseAllPlayers() {
        player?.release()
        player = null
        _binding?.playerView?.player = null
    }

    /**
     * 直播缓冲策略（v3.3.0 校准）：
     * 原 500/2000/300/500 对公网直播流过于激进——起播仅 300ms 缓冲，轻微抖动即
     * BUFFERING，配合事件计数换线导致"进频道几秒后卡住/像在切源"。
     * 新参数保留较快起播（bufferForPlayback 1000ms），同时给足抗抖动余量。
     */
    @OptIn(UnstableApi::class)
    private fun createFastLoadControl(): DefaultLoadControl {
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                8_000,
                15_000,
                1_000,
                2_500
            )
            .build()
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
            if (::viewModel.isInitialized) viewModel.setPlaybackActive(true)
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
        // Fragment lifecycle changes can precede ExoPlayer callbacks. Cancel
        // speculative work synchronously so it never outlives the viewing UI.
        if (::viewModel.isInitialized) viewModel.cancelBackgroundMaintenance()
        if (tvModel?.tv?.playerType == PlayerType.WEBVIEW) {
            Log.d(TAG, "Skipping pause for WEBVIEW")
            return
        }
        if (!SP.enableScreenOffAudio && player != null) {
            player?.pause()
            if (::viewModel.isInitialized) viewModel.setPlaybackActive(false)
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
                    if (::viewModel.isInitialized) viewModel.setPlaybackActive(false)
                    Log.d(TAG, "Paused player on SCREEN_OFF in ${if (isInPictureInPictureMode) "PiP" else "Full-Screen"} mode")
                }
            } else if (intent.action == Intent.ACTION_SCREEN_ON) {
                if (!SP.enableScreenOffAudio && player != null) {
                    if (::viewModel.isInitialized) viewModel.setPlaybackActive(true)
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
        if (::viewModel.isInitialized) viewModel.cancelBackgroundMaintenance()
        idleProbeJob?.cancel()
        releaseAllPlayers()
        try {
            requireActivity().unregisterReceiver(screenReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "unregisterReceiver failed: ${e.message}")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (::viewModel.isInitialized) viewModel.cancelBackgroundMaintenance()
        _binding = null
        handler.removeCallbacks(checkPlaybackRunnable)
        handler.removeCallbacks(stableSourceCheckRunnable)
        idleProbeJob?.cancel()
    }

    companion object {
        private const val TAG = "PlayerFragment"
    }
}
