package com.horsenma.yourtv

import android.annotation.SuppressLint
import kotlinx.coroutines.withTimeoutOrNull
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.KeyEvent.*
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import android.view.WindowManager
import android.widget.PopupWindow
import android.widget.RelativeLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.horsenma.yourtv.databinding.SettingsWebBinding
import kotlin.math.abs
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.*
import androidx.lifecycle.asFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.collect
import com.horsenma.yourtv.models.TVModel
import androidx.core.view.isVisible
import android.app.Dialog
import android.content.Intent
import androidx.annotation.RequiresApi
import com.horsenma.yourtv.Utils.ViewModelUtils
import androidx.core.content.edit
import androidx.recyclerview.widget.RecyclerView
import java.io.File


@Suppress("UNUSED_EXPRESSION", "DEPRECATION")
class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"
    internal var playerFragment = com.horsenma.yourtv.PlayerFragment()
    private var initializedReady = false
    internal var errorFragment = com.horsenma.yourtv.ErrorFragment()
    internal var loadingFragment = com.horsenma.yourtv.LoadingFragment()
    internal var infoFragment = com.horsenma.yourtv.InfoFragment()
    internal var channelFragment = com.horsenma.yourtv.ChannelFragment()
    internal var timeFragment = com.horsenma.yourtv.TimeFragment()
    internal var menuFragment = com.horsenma.yourtv.MenuFragment()
    internal var settingFragment = com.horsenma.yourtv.SettingFragment()
    internal var programFragment = com.horsenma.yourtv.ProgramFragment()
    internal var sourceSelectFragment = com.horsenma.yourtv.SourceSelectFragment()

    /**
     * Fragment 统一使用类名作为 tag：初始 add、懒加载 add 与进程重建后的
     * findFragmentByTag 全部一致，避免字段实例与 FragmentManager 恢复实例脱节
     * 导致黑屏/操作静默失效。
     */
    private fun fragmentTag(fragment: Fragment): String = fragment.javaClass.simpleName

    /** 进程重建（savedInstanceState != null）后，把字段重新绑定到 FragmentManager 恢复的实例 */
    private fun rebindRestoredFragments() {
        fun <T : Fragment> rebind(tag: String, fallback: T): T {
            @Suppress("UNCHECKED_CAST")
            return supportFragmentManager.findFragmentByTag(tag) as? T ?: fallback
        }
        playerFragment = rebind(fragmentTag(playerFragment), playerFragment)
        errorFragment = rebind(fragmentTag(errorFragment), errorFragment)
        loadingFragment = rebind(fragmentTag(loadingFragment), loadingFragment)
        infoFragment = rebind(fragmentTag(infoFragment), infoFragment)
        channelFragment = rebind(fragmentTag(channelFragment), channelFragment)
        timeFragment = rebind(fragmentTag(timeFragment), timeFragment)
        menuFragment = rebind(fragmentTag(menuFragment), menuFragment)
        settingFragment = rebind(fragmentTag(settingFragment), settingFragment)
        programFragment = rebind(fragmentTag(programFragment), programFragment)
        sourceSelectFragment = rebind(fragmentTag(sourceSelectFragment), sourceSelectFragment)
        Log.d(TAG, "Rebound restored fragments: player=" + playerFragment.isAdded + ", loading=" + loadingFragment.isAdded + ", menu=" + menuFragment.isAdded)
    }

    private val handler = Handler(Looper.myLooper()!!)
    private val delayHideMenu = 10 * 1000L
    private val delayHideSetting = 1 * 60 * 1000L
    lateinit var gestureDetector: GestureDetector
    private var server: SimpleServer? = null
    private lateinit var updateManager: UpdateManager
    private val sharedPrefs by lazy { getSharedPreferences("UpdatePrefs", MODE_PRIVATE) }

    private var lastSwitchTime = 0L
    private val DEBOUNCE_INTERVAL = 2000L
    private var lastBackPressTime = 0L
    private val BACK_PRESS_INTERVAL = 2000L
    private val watchedLikes = java.util.Collections.newSetFromMap(java.util.WeakHashMap<TVModel, Boolean>())

    internal lateinit var viewModel: MainViewModel

    private var isSafeToPerformFragmentTransactions = false
    internal var usersInfo: List<String> = emptyList()
    private var isLoadingInputVisible = false

    // 新增：禁用用户输入和画中画标志
    private var isInputDisabled = false

    fun setLoadingInputVisible(visible: Boolean) {
        isLoadingInputVisible = visible
    }

    private lateinit var userVerificationHandler: UserVerificationHandler
    private lateinit var dialog: Dialog
    private lateinit var verificationCallback: VerificationCallback
    private var lastSourceUpTime = 0L
    private val sourceUpDebounce = 2_000L

    // Callback interface for verification dialog
    interface VerificationCallback {
        fun onKeyConfirmed(key: String)
        fun onSkip()
        fun onCompleted()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 必须在 super.onCreate 之前初始化 viewModel：
        // super.onCreate 会恢复上一进程遗留的 Fragment（如 SourceSelectFragment.onCreate
        // 会直接访问 activity.viewModel），此时未初始化会触发
        // UninitializedPropertyAccessException —— 这正是进程重建后重进闪退/黑屏的根因之一。
        // MainViewModel 无 SavedStateHandle 构造参数，此处提前创建是安全的。
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]
        super.onCreate(savedInstanceState)
        updateFullScreenMode(SP.fullScreenMode)
        setContentView(R.layout.activity_main)

        UserInfoManager.initialize(applicationContext)
        userVerificationHandler = UserVerificationHandler(this, UserInfoManager, viewModel)

        val versionCode = packageManager.getPackageInfo(packageName, 0).versionCode.toLong()
        updateManager = UpdateManager(this, versionCode)

        // 初始化 dialog 和 verificationCallback
        dialog = Dialog(this)
        verificationCallback = object : VerificationCallback {
            override fun onKeyConfirmed(key: String) {
                Log.d(TAG, "Verification key confirmed: $key")
                setLoadingInputVisible(false)
            }
            override fun onSkip() {
                Log.d(TAG, "Verification skipped")
                setLoadingInputVisible(false)
            }
            override fun onCompleted() {
                Log.d(TAG, "Verification completed")
                setLoadingInputVisible(false)
                hideFragment(loadingFragment)
            }
        }

        // 初始化所有 Fragment
        if (savedInstanceState == null) {
            try {
                supportFragmentManager.beginTransaction()
                    .add(R.id.main_browse_fragment, playerFragment, fragmentTag(playerFragment))
                    .add(R.id.main_browse_fragment, infoFragment, fragmentTag(infoFragment))
                    .add(R.id.main_browse_fragment, channelFragment, fragmentTag(channelFragment))
                    .add(R.id.main_browse_fragment, menuFragment, fragmentTag(menuFragment))
                    .add(R.id.main_browse_fragment, settingFragment, fragmentTag(settingFragment))
                    .add(R.id.main_browse_fragment, sourceSelectFragment, fragmentTag(sourceSelectFragment))
                    // 加载页最后 add 置于最上层：解析期间不被播放器黑面遮挡
                    .add(R.id.main_browse_fragment, loadingFragment, fragmentTag(loadingFragment))
                    .hide(infoFragment)
                    .hide(channelFragment)
                    .hide(menuFragment)
                    .hide(settingFragment)
                    .hide(sourceSelectFragment)
                    .commitNow()
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Failed to add fragments: ${e.message}")
                supportFragmentManager.beginTransaction()
                    .add(R.id.main_browse_fragment, playerFragment, fragmentTag(playerFragment))
                    .add(R.id.main_browse_fragment, infoFragment, fragmentTag(infoFragment))
                    .add(R.id.main_browse_fragment, channelFragment, fragmentTag(channelFragment))
                    .add(R.id.main_browse_fragment, menuFragment, fragmentTag(menuFragment))
                    .add(R.id.main_browse_fragment, settingFragment, fragmentTag(settingFragment))
                    .add(R.id.main_browse_fragment, sourceSelectFragment, fragmentTag(sourceSelectFragment))
                    .add(R.id.main_browse_fragment, loadingFragment, fragmentTag(loadingFragment))
                    .hide(infoFragment)
                    .hide(channelFragment)
                    .hide(menuFragment)
                    .hide(settingFragment)
                    .hide(sourceSelectFragment)
                    .commit()
            }
        } else {
            // 进程重建：FragmentManager 已恢复旧实例，字段必须重新绑定，否则所有
            // show/hide/play 都作用在游离实例上（重进黑屏根因）。
            rebindRestoredFragments()
        }

        // 错误页重试入口：OK/点击"重试"按钮重新播放当前频道
        errorFragment.setRetryListener {
            retryCurrentPlayback()
        }

        // 注入 ViewModel（备用播放器预加载等需要访问频道列表）
        playerFragment.setViewModel(viewModel)

        // 设置全屏模式监听器
        YourTVApplication.getInstance().setFullScreenModeListener {
            if (playerFragment.isAdded) {
                playerFragment.onFullScreenModeChanged()
            }
        }

        // 设置手势检测
        gestureDetector = GestureDetector(this@MainActivity, GestureListener(this@MainActivity))

        // 优化 playTrigger 观察者
        viewModel.playTrigger.observe(this@MainActivity) { tvModel ->
            tvModel?.let {
                viewModel.setCurrentTvModel(it)
                viewModel.groupModel.setCurrent(it)
                lifecycleScope.launch(Dispatchers.Main) {
                    if (!playerFragment.isAdded) {
                        try {
                            supportFragmentManager.beginTransaction()
                                .add(R.id.main_browse_fragment, playerFragment, fragmentTag(playerFragment))
                                .commitNowAllowingStateLoss()
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to add PlayerFragment: ${e.message}", e)
                            showFragment(menuFragment)
                            menuActive()
                            return@launch
                        }
                    }
                    showFragment(playerFragment)

                    // 最多等待 2 秒
                    var attempts = 0
                    while (attempts < 20 && (!playerFragment.isAdded || playerFragment.view?.isAttachedToWindow != true)) {
                        Log.d(TAG, "Waiting for PlayerFragment view to be ready... attempt $attempts")
                        delay(100)
                        attempts++
                    }

                    if (playerFragment.isAdded && playerFragment.view != null && playerFragment.view?.isAttachedToWindow == true) {
                        playerFragment.play(it)
                        playerFragment.view?.requestLayout()
                        Log.d(TAG, "PlayerFragment shown, playing: ${it.tv.title}")
                    } else {
                        Log.w(TAG, "PlayerFragment view not ready, showing MenuFragment")
                        showFragment(menuFragment)
                        menuActive()
                    }
                }
            } ?: Log.w(TAG, "playTrigger received null TvModel")
        }

        lifecycleScope.launch(Dispatchers.Main) {
            try {
                withTimeoutOrNull(5000) { viewModel.init(this@MainActivity) } ?: run {
                    Log.w(TAG, "viewModel.init timed out after 5s")
                    showFragment(menuFragment)
                    menuActive()
                    R.string.initialization_error.showToast()
                    return@launch
                }

                var attempts = 0
                var currentTvModel: TVModel? = null
                while (attempts < 10 && currentTvModel == null && SP.getStableSources().isNotEmpty()) {
                    currentTvModel = viewModel.groupModel.getCurrent()
                    delay(100)
                    attempts++
                    Log.d(TAG, "Waiting for stable source, attempt $attempts, currentTvModel: ${currentTvModel?.tv?.title}")
                }

                if (currentTvModel != null && SP.getStableSources().isNotEmpty()) {
                    menuFragment.update()
                    menuFragment.updateList(viewModel.groupModel.positionValue)
                    Log.d(TAG, "Stable source already playing: ${currentTvModel.tv.title}...")
                    hideFragment(loadingFragment)
                    return@launch
                }

                // 兜底超时：channelsOk 30 秒内没就绪就进入菜单，避免无限黑屏
                val channelsLoaded = withTimeoutOrNull(30_000) {
                    viewModel.channelsOk.asFlow().takeWhile { !it }.collect()
                    viewModel.channelsOk.value == true
                } ?: false
                if (!channelsLoaded) {
                    Log.w(TAG, "Channels not loaded within 30s, showing menu")
                    hideFragment(loadingFragment)
                    showFragment(menuFragment)
                    menuActive()
                    return@launch
                }
                Log.d(TAG, "Channels loaded, channelsOk: ${viewModel.channelsOk.value}")
                hideFragment(loadingFragment)

                menuFragment.update()
                menuFragment.updateList(viewModel.groupModel.positionValue)

                // 起播兜底：
                // - init Step1 已通过 playTrigger 触发播放（稳定源或内置稳定频道），这里不打断；
                // - 没有任何频道被触发播放时，主动起播当前/首个频道，避免黑屏。
                if (playerFragment.tvModel == null) {
                    val target = viewModel.groupModel.current.value ?: viewModel.listModel.firstOrNull()
                    if (target != null) {
                        viewModel.groupModel.setCurrent(target)
                        viewModel.groupModel.setPositionPlaying()
                        viewModel.groupModel.getCurrentList()?.let {
                            it.setPosition(0)
                            it.setPositionPlaying()
                            it.getCurrent()?.setReady()
                        }
                        Log.d(TAG, "Init: no active playback, auto-playing ${target.tv.title}...")
                        viewModel.triggerPlay(target)
                    } else {
                        showFragment(menuFragment)
                        menuActive()
                        Log.w(TAG, "No tvModel available, showing MenuFragment")
                    }
                } else {
                    Log.d(TAG, "Channels loaded, playback already triggered: ${playerFragment.tvModel?.tv?.title}...")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Initialization failed: ${e.message}", e)
                showFragment(menuFragment)
                menuActive()
                R.string.initialization_error.showToast()
            }
        }
    }

    fun updateFullScreenMode(isFullScreen: Boolean) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowCompat.setDecorFitsSystemWindows(window, !isFullScreen)
            val params = window.attributes
            if (isFullScreen) {
                windowInsetsController.hide(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            } else {
                windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
                params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
            window.attributes = params
        } else {
            // API 23-27: 使用传统全屏方式
            if (isFullScreen) {
                window.setFlags(
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                )
            } else {
                window.clearFlags(
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                )
                // 不需要额外设置 FLAG_LAYOUT_STABLE，清除 FLAG_FULLSCREEN 后系统会自动调整内容适应系统栏
            }
        }

        // 设置系统栏行为，兼容低版本
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowInsetsController.systemBarsBehavior = if (isFullScreen) {
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            }
        } else {
            window.decorView.systemUiVisibility = if (isFullScreen) {
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            } else {
                View.SYSTEM_UI_FLAG_VISIBLE // 恢复默认可见状态
            }
        }

        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.decorView.requestLayout()
        window.decorView.invalidate()
        Log.d(TAG, "updateFullScreenMode: isFullScreen=$isFullScreen")

        if (isSafeToPerformFragmentTransactions && playerFragment.isAdded && !playerFragment.isInPictureInPictureMode) {
            handler.removeCallbacksAndMessages(null)
            handler.post {
                playerFragment.onFullScreenModeChanged()
                playerFragment.view?.findViewById<View>(R.id.player_view)?.let { playerView ->
                    playerView.requestFocus()
                    playerView.requestLayout()
                }
                val displayMetrics = resources.displayMetrics
                Log.d(TAG, "Window size: width=${displayMetrics.widthPixels}, height=${displayMetrics.heightPixels}")
            }
        }
    }

    fun updateMenuSize() {
        menuFragment.updateSize()
    }

    fun updateMenu() {
        val menuFragment = supportFragmentManager.findFragmentByTag("MenuFragment") as? MenuFragment
        menuFragment?.update()
    }

    fun ready() {
        // 幂等单次初始化：不再依赖 LoadingFragment + WebFragment 恰好各触发一次的
        // ok==2 巧合计数（首装首频道为 IPTV 时永远到不了 2，导致观察者/服务不启动）。
        if (initializedReady) return
        initializedReady = true
        Log.d(TAG, "ready(): running one-time initialization")
        try {
            gestureDetector = GestureDetector(this, GestureListener(this))
            // 确保 Fragment 状态正确
            supportFragmentManager.beginTransaction()
                .hide(menuFragment)
                .hide(settingFragment)
                .hide(sourceSelectFragment)
                .commit()
            viewModel.groupModel.change.observe(this) { _ ->
                if (viewModel.groupModel.tvGroup.value != null) {
                    watch()
                    menuFragment.update()
                }
            }

            // 切台预热：提前建立下一频道/下一条线路的连接，切台秒开
            viewModel.groupModel.current.observe(this) { tvModel ->
                if (tvModel != null) {
                    // 电视等弱机不做连接预热（省带宽/省电），只保留触屏设备秒切体验
                    if (isTouchScreenDevice()) {
                        viewModel.groupModel.getNext()?.let { next ->
                            playerFragment.prewarm(next.getVideoUrl())
                        }
                        // 只预热当前频道当前线路的下一条（自动换线用），避免并发请求挤占网络
                        tvModel.tv.uris.getOrNull(tvModel.videoIndexValue + 1)?.let { nextLine ->
                            playerFragment.prewarm(nextLine)
                        }
                    }
                }
            }

            viewModel.channelsOk.observe(this) {
                if (it) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        menuFragment.update()
                        val currentGroup = viewModel.groupModel.positionValue
                        menuFragment.updateList(currentGroup)
                        viewModel.groupModel.isInLikeMode =
                            SP.defaultLike && viewModel.groupModel.positionValue == 0
                        lifecycleScope.launch(Dispatchers.IO) {
                            viewModel.updateEPG()
                        }
                    }
                }
            }

            Utils.isp.observe(this) {
                val id = when (it) {
                    else -> 0
                }

                if (id == 0) {
                    return@observe
                }

                resources.openRawResource(id).bufferedReader()
                    .use { i ->
                        val channels = i.readText()
                        if (channels.isNotEmpty()) {
                            viewModel.tryStr2Channels(channels, null, "")
                        } else {
                            Log.w(TAG, "$it is empty")
                        }
                    }
            }

            server = SimpleServer(this, viewModel)

            viewModel.updateConfig()
            if (playerFragment.isAdded && !playerFragment.isHidden) {
                val currentTvModel = viewModel.groupModel.getCurrent()
                if (currentTvModel != null) {
                    playerFragment.play(currentTvModel)
                } else {
                    Log.w(TAG, "No current TV model available")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "ready() initialization failed: ${e.message}", e)
        }
    }

    private fun <T> LiveData<T>.throttle(durationMs: Long): LiveData<T> {
        val result = MutableLiveData<T>()
        var lastEmission = 0L
        observeForever { value ->
            val now = System.currentTimeMillis()
            if (now - lastEmission >= durationMs) {
                result.value = value
                lastEmission = now
            }
        }
        return result
    }

    private fun watch() {
        viewModel.listModel.forEach { tvModel ->
            // 同一频道的节流观察只创建一次（增量 watch，避免重复注册观察者）
            if (tvModel.errInfoThrottled == null) {
                tvModel.errInfoThrottled = tvModel.errInfo.throttle(1000)
            }
            tvModel.errInfoThrottled!!.observe(this) { _ ->
                if (tvModel.errInfo.value != null && tvModel == viewModel.groupModel.getCurrent()) {
                    hideFragment(loadingFragment)
                    if (tvModel.errInfo.value == "") {
                        hideFragment(errorFragment)
                        showFragment(playerFragment)
                    } else {
                        Log.i(TAG, "${tvModel.tv.title} ${tvModel.errInfo.value.toString()}")
                        hideFragment(playerFragment)
                        errorFragment.setMsg(tvModel.errInfo.value.toString())
                        showFragment(errorFragment)
                    }
                }
            }

            if (tvModel.readyThrottled == null) {
                tvModel.readyThrottled = tvModel.ready.throttle(1000)
            }
            tvModel.readyThrottled!!.observe(this) { _ ->
                if (tvModel.ready.value != null && tvModel == viewModel.groupModel.getCurrent()) {
                    hideFragment(errorFragment)
                    playerFragment.play(tvModel)
                    infoFragment.show(tvModel)
                    if (SP.channelNum) {
                        channelFragment.show(tvModel)
                    }
                }
            }

            if (!watchedLikes.contains(tvModel)) {
                watchedLikes.add(tvModel)
                tvModel.like.observe(this) { _ ->
                    if (tvModel.like.value != null && tvModel.tv.id != -1) {
                        val liked = tvModel.like.value as Boolean
                        if (liked) {
                            viewModel.groupModel.getFavoritesList()?.replaceTVModel(tvModel)
                        } else {
                            viewModel.groupModel.getFavoritesList()?.removeTVModel(tvModel.tv.id)
                        }
                        SP.setLike(tvModel.tv.id, liked)
                    }
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        // 新增：禁用用户输入时拦截触摸
        if (isInputDisabled) {
            Log.d(TAG, "Touch input blocked until listModel initialized")
            return true
        }

        if (event != null && menuFragment.isVisible) {
            return super.onTouchEvent(event)
        }
        if (event != null) {
            // 检查是否点击在 btn_source 上，若是则不处理
            val btnSource = playerFragment.view?.findViewById<View>(R.id.btn_source)
            if (btnSource != null && btnSource.isVisible) {
                val buttonRect = android.graphics.Rect()
                btnSource.getGlobalVisibleRect(buttonRect)
                if (buttonRect.contains(event.rawX.toInt(), event.rawY.toInt())) {
                    return false // 让 PlayerFragment 的 btn_source 处理事件
                }
            }
            gestureDetector.onTouchEvent(event)
            return true
        }
        return super.onTouchEvent(event)
    }

    private inner class GestureListener(context: Context) :
        GestureDetector.SimpleOnGestureListener() {

        private var screenWidth: Int
        private var screenHeight: Int
        private val audioManager = context.getSystemService(AUDIO_SERVICE) as AudioManager

        private var maxVolume = 0

        init {
            maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val displayMetrics = resources.displayMetrics
            screenWidth = displayMetrics.widthPixels
            screenHeight = displayMetrics.heightPixels
        }

        override fun onDown(e: MotionEvent): Boolean {
            playerFragment.hideVolumeNow()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            // 单击：显示/隐藏当前频道信息卡（简单可预期）
            val infoView = infoFragment.view
            if (infoView != null && infoView.visibility == View.VISIBLE) {
                infoView.visibility = View.GONE
            } else {
                viewModel.groupModel.getCurrent()?.let { infoFragment.show(it) }
            }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val sourceButton = playerFragment.view?.findViewById<View>(R.id.btn_source)
            if (sourceButton != null && sourceButton.isVisible) {
                val buttonRect = android.graphics.Rect()
                sourceButton.getGlobalVisibleRect(buttonRect)
                if (buttonRect.contains(e.x.toInt(), e.y.toInt())) {
                    sourceUp()
                    return true
                }
            }

            // 双击：打开/关闭频道菜单（与遥控器 MENU 心智一致）
            if (menuFragment.isAdded && !menuFragment.isHidden) {
                hideFragment(menuFragment)
            } else {
                showFragment(menuFragment)
                menuActive()
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            showProgram()
            return
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            val oldX = e1?.rawX ?: 0f
            val oldY = e1?.rawY ?: 0f
            val newX = e2.rawX
            val newY = e2.rawY
            if (oldX > screenWidth / 3 && oldX < screenWidth * 2 / 3 && abs(newX - oldX) < abs(newY - oldY)) {
                if (velocityY > 0) {
                    if ((!menuFragment.isAdded || menuFragment.isHidden) && (!settingFragment.isAdded || settingFragment.isHidden)) {
                        prev()
                    }
                }
                if (velocityY < 0) {
                    if ((!menuFragment.isAdded || menuFragment.isHidden) && (!settingFragment.isAdded || settingFragment.isHidden)) {
                        next()
                    }
                }
            }

            return super.onFling(e1, e2, velocityX, velocityY)
        }

        private var lastScrollTime: Long = 0
        private var decayFactor: Float = 1.0f

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            val oldX = e1?.rawX ?: 0f
            val oldY = e1?.rawY ?: 0f
            val newX = e2.rawX
            val newY = e2.rawY

            if (oldX < screenWidth / 3) {
                val currentTime = System.currentTimeMillis()
                val deltaTime = currentTime - lastScrollTime
                lastScrollTime = currentTime

                decayFactor =
                    0.01f.coerceAtLeast(decayFactor - 0.03f * deltaTime)
                val delta =
                    ((oldY - newY) * decayFactor * 0.2 / screenHeight).toFloat()
                adjustBrightness(delta)
                decayFactor = 1.0f
                return super.onScroll(e1, e2, distanceX, distanceY)
            }

            if (oldX > screenWidth * 2 / 3 && abs(distanceY) > abs(distanceX)) {
                val currentTime = System.currentTimeMillis()
                val deltaTime = currentTime - lastScrollTime
                lastScrollTime = currentTime

                decayFactor =
                    0.01f.coerceAtLeast(decayFactor - 0.03f * deltaTime)
                val delta =
                    ((oldY - newY) * maxVolume * decayFactor * 0.2 / screenHeight).toInt()
                adjustVolume(delta)
                decayFactor = 1.0f
                return super.onScroll(e1, e2, distanceX, distanceY)
            }

            return super.onScroll(e1, e2, distanceX, distanceY)
        }

        private fun adjustVolume(deltaVolume: Int) {
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

            var newVolume = currentVolume + deltaVolume

            if (newVolume < 0) {
                newVolume = 0
            } else if (newVolume > maxVolume) {
                newVolume = maxVolume
            }

            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)

            playerFragment.setVolumeMax(maxVolume * 100)
            playerFragment.setVolume(newVolume.toInt() * 100, true)
            playerFragment.showVolume(View.VISIBLE)
        }

        private fun adjustBrightness(deltaBrightness: Float) {
            var brightness = window.attributes.screenBrightness

            brightness += deltaBrightness
            brightness = 0.1f.coerceAtLeast(0.9f.coerceAtMost(brightness))

            val attributes = window.attributes.apply {
                screenBrightness = brightness
            }
            window.attributes = attributes

            playerFragment.setVolumeMax(100)
            playerFragment.setVolume((brightness * 100).toInt())
            playerFragment.showVolume(View.VISIBLE)
        }
    }

    fun onPlayEnd() {
        val tvModel = viewModel.groupModel.getCurrent()!!
        if (SP.repeatInfo) {
            infoFragment.show(tvModel)
            if (SP.channelNum) {
                channelFragment.show(tvModel)
            }
        }
    }

    fun play(position: Int): Boolean {
        return if (position > -1 && position < viewModel.groupModel.getAllList()!!.size()) {
            val prevGroup = viewModel.groupModel.positionValue
            val tvModel = viewModel.groupModel.getPosition(position)

            tvModel?.setReady()
            viewModel.groupModel.setPositionPlaying()
            viewModel.groupModel.getCurrentList()?.setPositionPlaying()

            val currentGroup = viewModel.groupModel.positionValue
            if (currentGroup != prevGroup) {
                menuFragment.updateList(currentGroup)
            }
            true
        } else {
            R.string.channel_not_exist.showToast()
            false
        }
    }

    fun prev() {
        val prevGroup = viewModel.groupModel.positionValue
        val tvModel =
            if (SP.defaultLike && viewModel.groupModel.isInLikeMode && viewModel.groupModel.getFavoritesList() != null
            ) {
                viewModel.groupModel.getPrev(true)
            } else {
                viewModel.groupModel.getPrev()
            }

        tvModel?.setReady()
        viewModel.groupModel.setPositionPlaying()
        viewModel.groupModel.getCurrentList()?.setPositionPlaying()

        val currentGroup = viewModel.groupModel.positionValue
        if (currentGroup != prevGroup) {
            menuFragment.updateList(currentGroup)
        }
    }

    fun next() {
        val prevGroup = viewModel.groupModel.positionValue
        val tvModel =
            if (SP.defaultLike && viewModel.groupModel.isInLikeMode && viewModel.groupModel.getFavoritesList() != null
            ) {
                viewModel.groupModel.getNext(true)
            } else {
                viewModel.groupModel.getNext()
            }

        tvModel?.setReady()
        viewModel.groupModel.setPositionPlaying()
        viewModel.groupModel.getCurrentList()?.setPositionPlaying()

        val currentGroup = viewModel.groupModel.positionValue
        if (currentGroup != prevGroup) {
            menuFragment.updateList(currentGroup)
        }
    }

    // 更新 showFragment 方法，确保画中画模式下视图可见
    internal fun showFragment(fragment: Fragment) {
        if (!isSafeToPerformFragmentTransactions) {
            return
        }
        val transaction = supportFragmentManager.beginTransaction()
        if (!fragment.isAdded) {
            transaction.add(R.id.main_browse_fragment, fragment, fragmentTag(fragment))
        } else if (!fragment.isHidden) {
            return
        }
        transaction.show(fragment)
        try {
            transaction.commitNow()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Fragment transaction failed, falling back to commit: ${e.message}")
            transaction.commit()
        }
        fragment.view?.visibility = View.VISIBLE
    }

    private fun hideFragment(fragment: Fragment) {
        if (!isSafeToPerformFragmentTransactions || !fragment.isAdded || fragment.isHidden) {
            return
        }
        val transaction = supportFragmentManager.beginTransaction()
        transaction.hide(fragment)
        try {
            transaction.commitNow()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Fragment hide transaction failed, falling back to commit: ${e.message}")
            transaction.commit()
        }
    }

    fun sourceUp(showToast: Boolean = true) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSourceUpTime < sourceUpDebounce) {
            Log.d(TAG, "Debounced sourceUp for ${viewModel.groupModel.getCurrent()?.tv?.title}")
            return
        }
        lastSourceUpTime = currentTime

        var tvModel = viewModel.groupModel.getCurrent()
        if (tvModel == null) {
            Log.w(TAG, "sourceUp: tvModel is null, attempting to fix groupModel")
            if (viewModel.listModel.isNotEmpty()) {
                tvModel = viewModel.listModel[SP.channel.coerceIn(0, viewModel.listModel.size - 1)]
                viewModel.groupModel.setCurrent(tvModel)
                Log.d(TAG, "Fixed groupModel with tvModel: ${tvModel.tv.title}, uris: ${tvModel.tv.uris.size}")
            } else {
                Log.e(TAG, "sourceUp: listModel is empty")
                R.string.no_current_tv_model.showToast()
                return
            }
        }

        val urls = tvModel.tv.uris.filter { it.isNotBlank() }
        if (urls.isEmpty()) {
            Log.w(TAG, "sourceUp: no available sources for ${tvModel.tv.title}")
            R.string.no_available_sources.showToast()
            return
        }
        if (urls.size <= 1) {
            Log.d(TAG, "sourceUp: only one source for ${tvModel.tv.title}")
            R.string.no_multiple_sources.showToast()
            return
        }

        // switchSource 内部统一切换下一条健康线路
        playerFragment.switchSource(tvModel, showToast)
        if (showToast) {
            showSourceInfo(tvModel.videoIndexValue + 1, urls.size)
        }
        Log.d(TAG, "sourceUp: switched to source ${tvModel.videoIndexValue + 1}, uris: ${tvModel.tv.uris.size}")
    }

    private fun showSourceInfo(sourceIndex: Int, totalSources: Int) {
        val toast = Toast.makeText(
            this,
            "线路 $sourceIndex / $totalSources",
            Toast.LENGTH_LONG
        )
        val textView = toast.view?.findViewById<TextView>(android.R.id.message)
        textView?.textSize = 30f
        toast.setGravity(Gravity.CENTER, 0, 0)
        toast.show()

        handler.postDelayed({
            toast.cancel()
        }, 5000)
    }

    fun menuActive() {
        handler.removeCallbacks(hideMenu)
        handler.postDelayed(hideMenu, delayHideMenu)
    }

    private val hideMenu = Runnable {
        if (!isFinishing && !supportFragmentManager.isStateSaved) {
            if (!menuFragment.isHidden) {
                supportFragmentManager.beginTransaction()
                    .hide(menuFragment)
                    .commitAllowingStateLoss()
            }
        }
    }

    fun switchSoftDecode() {
        if (!playerFragment.isAdded || playerFragment.isHidden) {
            return
        }

        playerFragment.rebuildPlayers()
    }

    fun settingActive() {
        handler.removeCallbacks(hideSetting)
        handler.postDelayed(hideSetting, delayHideSetting)
    }

    private val hideSetting = Runnable {
        hideFragment(settingFragment)
        showTimeFragment()
    }

    fun showTimeFragment() {
        if (SP.time) {
            showFragment(timeFragment)
        } else {
            hideFragment(timeFragment)
        }
    }

    private fun scheduleAutoVersionCheck() {
        // 检查是否需要自动检查（24小时内只检查一次）
        val lastCheckTime = sharedPrefs.getLong("last_auto_check_time", 0)
        val currentTime = System.currentTimeMillis()
        val checkInterval = 24 * 60 * 60 * 1000L // 24小时
        if (currentTime - lastCheckTime < checkInterval) {
            Log.d(TAG, "Auto version check skipped, last check within 24 hours")
            return
        }

        // 延时3秒触发版本检查
        handler.postDelayed({
            // 确保设置界面未打开，避免干扰用户操作
            if (settingFragment.isAdded && !settingFragment.isHidden) {
                Log.d(TAG, "SettingFragment is visible, skipping auto version check")
                return@postDelayed
            }

            Log.d(TAG, "Triggering auto version check")
            // 获取当前版本号
            val currentVersionCode = packageManager.getPackageInfo(packageName, 0).versionCode.toLong()

            // 获取存储的版本信息和检查次数
            val lastDetectedVersion = sharedPrefs.getLong("last_detected_version", 0)
            val updateCheckCount = sharedPrefs.getInt("update_check_count", 0)
            val firstUpdateDetectedTime = sharedPrefs.getLong("first_update_detected_time", 0)

            // 执行版本检查
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val release = updateManager.getRelease() // 获取版本信息
                    updateManager.release = release // 更新 UpdateManager 的 release
                    val versionCodeFromRelease = release?.version_code

                    // 记录检查时间
                    sharedPrefs.edit() {
                        putLong("last_auto_check_time", currentTime)
                    }

                    // 如果检测到新版本
                    if (versionCodeFromRelease != null && versionCodeFromRelease > currentVersionCode) {
                        if (lastDetectedVersion == 0L) {
                            // 首次发现新版本，记录时间和当前版本号
                            sharedPrefs.edit() {
                                putLong("first_update_detected_time", currentTime)
                                    .putLong("last_detected_version", currentVersionCode)
                                    .putInt("update_check_count", 1)
                            }
                            Log.d(TAG, "First update detected, version: $currentVersionCode, time: $currentTime")
                        } else if (lastDetectedVersion == currentVersionCode) {
                            // 非首次检查，版本未更新，增加检查次数
                            val newCount = updateCheckCount + 1
                            sharedPrefs.edit() {
                                putInt("update_check_count", newCount)
                            }
                            Log.d(TAG, "Update check count incremented to $newCount")

                            // 检查次数达到 3 次或 5 次，显示“必须更新”提示
                            if (newCount == 3 || newCount == 4) {
                                Toast.makeText(this@MainActivity, R.string.please_update, Toast.LENGTH_LONG).show()
                                Log.d(TAG, "Displayed mandatory update prompt at check count $newCount")
                            }
                            if (newCount == 5) {
                                Toast.makeText(this@MainActivity, R.string.force_update_soon, Toast.LENGTH_LONG).show()
                                Log.d(TAG, "Displayed mandatory update prompt at check count $newCount")
                            }
                            // 检查次数达到 6 次，显示提示并退出
                            else if (newCount >= 6) {
                                val toast = Toast.makeText(this@MainActivity, R.string.too_old_version, Toast.LENGTH_LONG)
                                toast.setGravity(Gravity.CENTER, 0, 0)
                                toast.show()
                                Log.d(TAG, "Displayed force update prompt at check count $newCount, exiting in 10s")
                                handler.postDelayed({
                                    finishAffinity()
                                }, 10000) // 10秒后退出
                            }
                        } else {
                            // 版本已更新，重置计数和记录
                            sharedPrefs.edit() {
                                putLong("last_detected_version", 0)
                                    .putInt("update_check_count", 0)
                                    .putLong("first_update_detected_time", 0)
                            }
                            Log.d(TAG, "Version updated, reset update check count and records")
                        }
                    } else {
                        // 无新版本，静默结束
                        Log.d(TAG, "No new version available, versionCode=$currentVersionCode, remote=$versionCodeFromRelease")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Version check failed: ${e.message}", e)
                    // 记录检查时间，即使失败
                    sharedPrefs.edit() {
                        putLong("last_auto_check_time", currentTime)
                    }
                }
            }
        }, 3000) // 延时3秒
    }

    private fun showChannel(channel: Int) {
        if (!menuFragment.isHidden) {
            return
        }

        if (settingFragment.isVisible) {
            return
        }

//        if (SP.channelNum) {
//            channelFragment.show(channel)
//        }
        channelFragment.show(channel)
    }


    private fun channelUp() {
        if (programFragment.isAdded && !programFragment.isHidden) {
            return
        }

        if ((!menuFragment.isAdded || menuFragment.isHidden) && (!settingFragment.isAdded || settingFragment.isHidden)) {
            if (SP.channelReversal) {
                next()
                return
            }
            prev()
        }
    }

    private fun channelDown() {
        if (programFragment.isAdded && !programFragment.isHidden) {
            return
        }

        if ((!menuFragment.isAdded || menuFragment.isHidden) && (!settingFragment.isAdded || settingFragment.isHidden)) {
            if (SP.channelReversal) {
                prev()
                return
            }
            next()
        }
    }

    fun showSetting() {
        lifecycleScope.launch(Dispatchers.Main) {
            if (programFragment.isAdded && !programFragment.isHidden) {
                hideFragment(programFragment)
            }
            if (menuFragment.isAdded && !menuFragment.isHidden) {
                hideFragment(menuFragment)
            }
            showFragment(settingFragment)
            settingActive()
        }
    }

    // 错误页重试：重新触发当前频道播放（内部会跳过坏线、选健康线路）
    fun retryCurrentPlayback() {
        val tvModel = viewModel.groupModel.getCurrent() ?: return
        if (isSafeToPerformFragmentTransactions) {
            hideFragment(errorFragment)
            showFragment(playerFragment)
        }
        playerFragment.play(tvModel)
        Log.d(TAG, "retryCurrentPlayback: ${tvModel.tv.title}")
    }

    private fun showProgram() {
        if (menuFragment.isAdded && !menuFragment.isHidden) {
            return
        }

        if (settingFragment.isAdded && !settingFragment.isHidden) {
            return
        }

        viewModel.groupModel.getCurrent()?.let {
            if (it.epgValue.isEmpty()) {
                R.string.epg_is_empty.showToast()
                return
            }
        }

        showFragment(programFragment)
    }

    private fun hideProgram(): Boolean {
        if (!programFragment.isAdded || programFragment.isHidden) {
            return false
        }

        hideFragment(programFragment)
        return true
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun showWebViewPopup(url: String) {
        val binding = SettingsWebBinding.inflate(layoutInflater)

        val webView = binding.web
        webView.settings.javaScriptEnabled = true
        webView.isFocusableInTouchMode = true
        webView.isFocusable = true
        webView.loadUrl(url)

        val popupWindow = PopupWindow(
            binding.root,
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.MATCH_PARENT
        )

        popupWindow.inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
        popupWindow.isFocusable = true
        popupWindow.isTouchable = true

        popupWindow.isClippingEnabled = false

        popupWindow.showAtLocation(window.decorView, Gravity.CENTER, 0, 0)

        webView.requestFocus()

        binding.close.setOnClickListener {
            popupWindow.dismiss()
        }
    }

    @SuppressLint("GestureBackNavigation")
    fun onKey(keyCode: Int): Boolean {
        // 优先检查 SourceSelectFragment 是否可见
        if (sourceSelectFragment.isAdded && sourceSelectFragment.isVisible) {
            when (keyCode) {
                KEYCODE_ESCAPE, KEYCODE_BACK -> {
                    sourceSelectFragment.hideSelf()
                    return true
                }
                KEYCODE_DPAD_UP, KEYCODE_DPAD_DOWN -> {
                    // 直接请求 RecyclerView 的焦点导航
                    val recyclerView = sourceSelectFragment.view?.findViewById<RecyclerView>(R.id.source_list)
                    val currentFocus = recyclerView?.findFocus() ?: recyclerView
                    val nextFocus = currentFocus?.focusSearch(
                        if (keyCode == KEYCODE_DPAD_UP) View.FOCUS_UP else View.FOCUS_DOWN
                    )
                    nextFocus?.requestFocus()
                    return true
                }
                KEYCODE_DPAD_LEFT, KEYCODE_DPAD_RIGHT -> {
                    // 忽略左右键
                    return true
                }
                KEYCODE_ENTER, KEYCODE_DPAD_CENTER -> {
                    // 触发当前焦点的点击
                    sourceSelectFragment.view?.findFocus()?.performClick()
                    return true
                }
                else -> {
                    // 其他按键分发到 RecyclerView
                    sourceSelectFragment.view?.findViewById<RecyclerView>(R.id.source_list)?.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                    return true
                }
            }
        }
        when (keyCode) {
            KEYCODE_ESCAPE, KEYCODE_BACK -> {
                if (menuFragment.isAdded && !menuFragment.isHidden) {
                    // 三级列表下钻状态优先返回上一级，再关闭菜单
                    if (menuFragment.onBackPressed()) {
                        return true
                    }
                    hideFragment(menuFragment)
                    return true
                }
                if (settingFragment.isAdded && !settingFragment.isHidden) {
                    hideFragment(settingFragment)
                    showTimeFragment()
                    return true
                }
                if (programFragment.isAdded && !programFragment.isHidden) {
                    hideFragment(programFragment)
                    return true
                }
                if (channelFragment.isAdded && channelFragment.isVisible) {
                    channelFragment.hideSelf()
                    return true
                }
                if (sourceSelectFragment.isAdded && sourceSelectFragment.isVisible) {
                    sourceSelectFragment.hideSelf()
                    return true
                }
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastBackPressTime < BACK_PRESS_INTERVAL) {
                    finishAffinity()
                    return true
                }
                lastBackPressTime = currentTime
                R.string.press_back_exit.showToast()
                return true
            }
            KEYCODE_0, KEYCODE_1, KEYCODE_2, KEYCODE_3, KEYCODE_4,
            KEYCODE_5, KEYCODE_6, KEYCODE_7, KEYCODE_8, KEYCODE_9 -> {
                // 数字直拨守卫：数据未就绪时不崩溃，给出加载提示
                if (viewModel.groupModel.tvGroupValue.size < 3 ||
                    viewModel.groupModel.getAllList()?.size() == 0
                ) {
                    R.string.loading.showToast()
                    return true
                }
                showChannel(keyCode - 7)
                return true
            }
            KEYCODE_BOOKMARK, KEYCODE_UNKNOWN, KEYCODE_HELP,
            KEYCODE_MENU -> {
                // 单按 MENU 直达频道菜单（主流遥控器心智）
                if (menuFragment.isAdded && !menuFragment.isHidden) {
                    // 直接返回 false，让 MenuFragment 的 onKeyListener 处理
                    return false
                }
                showFragment(menuFragment)
                menuActive()
                return true
            }
            KEYCODE_SETTINGS -> {
                // 单按 SETTINGS 直达设置（主流遥控器心智）
                if (settingFragment.isAdded && !settingFragment.isHidden) {
                    return false
                }
                showSetting()
                return true
            }
            KEYCODE_DPAD_UP, KEYCODE_CHANNEL_UP -> {
                if (isLoadingInputVisible) {
                    if (userVerificationHandler.isInputUIVisible()) {
                        return true // 焦点切换由 XML 的 nextFocusUp 处理
                    }
                }
                if (menuFragment.isAdded && !menuFragment.isHidden) {
                    return false
                }
                if (settingFragment.isAdded && !settingFragment.isHidden) {
                    return false
                }
                channelUp()
                return true
            }

            KEYCODE_DPAD_DOWN, KEYCODE_CHANNEL_DOWN -> {
                if (isLoadingInputVisible) {
                    if (userVerificationHandler.isInputUIVisible()) {
                        return true // 焦点切换由 XML 的 nextFocusDown 处理
                    }
                }
                if (menuFragment.isAdded && !menuFragment.isHidden) {
                    return false
                }
                if (settingFragment.isAdded && !settingFragment.isHidden) {
                    return false
                }
                channelDown()
                return true
            }

            KEYCODE_ENTER, KEYCODE_DPAD_CENTER -> {
                if (isLoadingInputVisible) {
                    if (userVerificationHandler.isInputUIVisible()) {
                        val currentFocus = currentFocus
                        if (currentFocus?.id == R.id.confirm_button) {
                            val key = userVerificationHandler.getKeyInputText()?.trim() ?: ""
                            if (key.isNotEmpty() && key.matches("[0-9A-Z]{1,20}".toRegex())) {
                                userVerificationHandler.triggerConfirm(key, dialog, verificationCallback)
                            } else {
                                userVerificationHandler.showErrorText(getString(R.string.error_invalid_code))
                                userVerificationHandler.requestKeyInputFocus()
                            }
                            settingActive() // 新增：确认按钮按键重置计时器
                            return true
                        } else if (currentFocus?.id == R.id.skip_button) {
                            userVerificationHandler.triggerSkip(dialog, verificationCallback)
                            settingActive() // 新增：确认按钮按键重置计时器
                            return true
                        }
                        settingActive() // 新增：确认按钮按键重置计时器
                        return true
                    }
                }
                if (channelFragment.isAdded && channelFragment.isVisible) {
                    channelFragment.playNow()
                    return true
                }
                // EPG 打开时 OK = 关闭节目单（不再弹出频道菜单）
                if (programFragment.isAdded && !programFragment.isHidden) {
                    hideFragment(programFragment)
                    return true
                }
                if (menuFragment.isAdded && !menuFragment.isHidden) {
                    return false
                }
                if (settingFragment.isAdded && !settingFragment.isHidden) {
                    return false
                }
                // 播放界面单按 OK = 打开频道菜单
                showFragment(menuFragment)
                menuActive()
                return true
            }

            KEYCODE_DPAD_LEFT -> {
                if (isLoadingInputVisible) {
                    val loadingFragment = supportFragmentManager.findFragmentByTag(LoadingFragment.TAG) as? LoadingFragment
                    if (loadingFragment != null && loadingFragment.isVisible && userVerificationHandler.isInputUIVisible()) {
                        val currentFocus = currentFocus
                        if (currentFocus?.id == R.id.skip_button) {
                            val confirmButton = loadingFragment.view?.findViewById<View>(R.id.confirm_button)
                            confirmButton?.isFocusable = true
                            confirmButton?.isFocusableInTouchMode = true
                            confirmButton?.requestFocus()
                            return true
                        }
                        return true
                    }
                }
                if (settingFragment.isAdded && !settingFragment.isHidden) {
                    return false
                }
                showProgram()
                return true
            }

            KEYCODE_DPAD_RIGHT -> {
                if (isLoadingInputVisible) {
                    val loadingFragment = supportFragmentManager.findFragmentByTag(LoadingFragment.TAG) as? LoadingFragment
                    if (loadingFragment != null && loadingFragment.isVisible && userVerificationHandler.isInputUIVisible()) {
                        val currentFocus = currentFocus
                        if (currentFocus?.id == R.id.confirm_button) {
                            val skipButton = loadingFragment.view?.findViewById<View>(R.id.skip_button)
                            skipButton?.isFocusable = true
                            skipButton?.isFocusableInTouchMode = true
                            skipButton?.requestFocus()
                            return true
                        }
                        return true
                    }
                }
                if (menuFragment.isAdded && !menuFragment.isHidden ||
                    settingFragment.isAdded && !settingFragment.isHidden ||
                    programFragment.isAdded && !programFragment.isHidden) {
                    return false
                }
                // 单按右键 = 切换线路（sourceUp 内部自带 2s 防抖）
                sourceUp()
                return true
            }
        }
        return false
    }

    // 处理主页按钮点击（圆圈虚拟按钮）
    override fun onUserLeaveHint() {
        if (isInputDisabled) {
            Log.d(TAG, "Picture-in-Picture blocked until listModel initialized")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            isTouchScreenDevice() &&
            playerFragment.isAdded && !playerFragment.isHidden) {
            playerFragment.enterPictureInPictureMode()
            Log.d(TAG, "Entering Picture-in-Picture mode via onUserLeaveHint")
        } else {
            Log.d(TAG, "Skipped Picture-in-Picture: SDK=${Build.VERSION.SDK_INT}, isTouchScreen=${isTouchScreenDevice()}, playerFragmentAdded=${playerFragment.isAdded}, playerFragmentHidden=${playerFragment.isHidden}")
            super.onUserLeaveHint()
        }
    }

    // 保留原有 onKeyDown，仅处理返回键
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isInputDisabled) {
            Log.d(TAG, "Key input blocked until listModel initialized, keyCode=$keyCode")
            return true
        }
        if (onKey(keyCode)) {
            return true
        }
        // 不调用 super.onKeyDown，阻止系统默认退出
        return false
    }

    // 新增：触摸屏检测方法，与 PlayerFragment 一致
    private fun isTouchScreenDevice(): Boolean {
        val packageManager = packageManager
        val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
        val isTv = uiModeManager?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        val hasTouchScreen = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        return hasTouchScreen && !isTv
    }

    // 处理画中画模式变化
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode) {
            hideFragment(menuFragment)
            hideFragment(settingFragment)
            hideFragment(programFragment)
            hideFragment(channelFragment)
            hideFragment(infoFragment)
            hideFragment(timeFragment)
            hideFragment(errorFragment)
            hideFragment(loadingFragment)
            hideFragment(sourceSelectFragment)
            if (playerFragment.isAdded) {
                playerFragment.enterPictureInPictureMode()
            }
            showFragment(playerFragment)
            Log.d(TAG, "Entered Picture-in-Picture mode")
        } else {
            showFragment(playerFragment)
            if (playerFragment.isAdded) {
                playerFragment.exitPictureInPictureMode()
            }
            findViewById<View>(R.id.main_browse_fragment)?.requestFocus()
            showTimeFragment()
            if (SP.channelNum && viewModel.groupModel.getCurrent() != null) {
                channelFragment.show(viewModel.groupModel.getCurrent()!!)
            }
            Log.d(TAG, "Exited Picture-in-Picture mode, focus requested on main_browse_fragment")
        }
    }

    override fun onResume() {
        super.onResume()
        isSafeToPerformFragmentTransactions = true
        showTimeFragment()
        // 从后台恢复时继续播放（onStop 只暂停不释放）
        if (playerFragment.isAdded && playerFragment.player != null) {
            playerFragment.player?.play()
        }
    }

    // 在 onPause 中暂停播放并释放资源
    override fun onPause() {
        super.onPause()
        isSafeToPerformFragmentTransactions = false
    }

    override fun onStop() {
        super.onStop()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (playerFragment.isAdded && playerFragment.player != null && powerManager.isInteractive) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode) {
                Log.d(TAG, "In Picture-in-Picture mode, skipping player release and process termination")
                return
            }
            // 暂停播放而不是释放：保留播放器，回来秒恢复，避免黑屏
            playerFragment.player?.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop()
        handler.removeCallbacksAndMessages(null)
        updateManager.destroy()
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
    }

    fun getViewModel(): MainViewModel {
        return viewModel
    }

    fun handleWebviewTypeSwitch(enable: Boolean) {
        if (!enable) return
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSwitchTime < DEBOUNCE_INTERVAL) {
            Log.d(TAG, "Switch ignored due to debounce")
            return
        }
        lastSwitchTime = currentTime

        lifecycleScope.launch(Dispatchers.Main) {
            try {
                if (playerFragment.isAdded && playerFragment.player != null) {
                    playerFragment.player?.stop()
                    playerFragment.player?.release()
                    playerFragment.player = null
                    Log.d(TAG, "PlayerFragment resources released")
                }
                ViewModelUtils.cancelViewModelJobs(viewModel)
                supportFragmentManager.beginTransaction()
                    .hide(playerFragment)
                    .hide(infoFragment)
                    .hide(channelFragment)
                    .hide(menuFragment)
                    .hide(settingFragment)
                    .hide(programFragment)
                    .hide(timeFragment)
                    .hide(errorFragment)
                    .hide(loadingFragment)
                    .hide(sourceSelectFragment)
                    .commitNow()
                Log.d(TAG, "All fragments hidden")
                com.horsenma.yourtv.SP.enableWebviewType = true
                Log.d(TAG, "SP.enableWebviewType set to true")
                delay(500)
                val intent = Intent(this@MainActivity, com.horsenma.mytv1.MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
                finish()
                Log.d(TAG, "Switched to mytv1.MainActivity with new task")
            } catch (e: Exception) {
                Log.e(TAG, "Error switching to mytv1.MainActivity: ${e.message}", e)
                R.string.switch_webview_failed.showToast()
            }
        }
    }

    fun switchSource(filename: String, url: String) {
        Toast.makeText(this, "正在切换直播源，请稍候再操作...", Toast.LENGTH_LONG).show()
        val viewModel = ViewModelProvider(this)[MainViewModel::class.java]
        val prefs = getSharedPreferences("SourceCache", Context.MODE_PRIVATE)
        lifecycleScope.launch {
            try {
                val cacheFile = File(filesDir, "cache_$filename")
                val cachedContent = if (cacheFile.exists()) cacheFile.readText() else null
                if (cachedContent != null && System.currentTimeMillis() - prefs.getLong("cache_time_$filename", 0) < 24 * 60 * 60 * 1000) {
                    Log.d(TAG, "switchSource: Using cache for filename=$filename")
                    viewModel.tryStr2Channels(cachedContent, null, "", filename)
                    prefs.edit().putString("active_source", filename).apply()
                    supportFragmentManager.findFragmentByTag("MenuFragment")?.let { (it as MenuFragment).update() }
                    Toast.makeText(this@MainActivity, "直播源切换成功", Toast.LENGTH_SHORT).show()
                } else {
                    Log.w(TAG, "switchSource: Invalid cache for filename=$filename, url=$url")
                    viewModel.importFromUrl(url, filename, skipHistory = true)
                    prefs.edit().putString("active_source", filename).apply()
                    supportFragmentManager.findFragmentByTag("MenuFragment")?.let { (it as MenuFragment).update() }
                    Toast.makeText(this@MainActivity, "直播源切换成功", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "switchSource: Failed for filename=$filename: ${e.message}")
                viewModel.reset(this@MainActivity)
                prefs.edit().putString("active_source", "default_channels.txt").apply()
                supportFragmentManager.findFragmentByTag("MenuFragment")?.let { (it as MenuFragment).update() }
                Toast.makeText(this@MainActivity, "切换失败，使用默认源", Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        internal const val TAG = "MainActivity"
    }
}
