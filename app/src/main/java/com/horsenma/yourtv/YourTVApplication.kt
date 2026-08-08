package com.horsenma.yourtv

import android.app.Application
import android.content.Context
import android.content.res.Resources
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import com.tencent.smtt.export.external.TbsCoreSettings
import com.tencent.smtt.sdk.QbSdk
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security


class YourTVApplication : Application() {

    companion object {
        private const val TAG = "YourTVApplication"
        private const val KEY_FULL_SCREEN_MODE = "full_screen_mode" // 全屏模式键
        private lateinit var instance: YourTVApplication

        @JvmStatic
        fun getInstance(): YourTVApplication {
            return instance
        }
    }

    private var isX5Initialized = false
    private lateinit var displayMetrics: DisplayMetrics
    private lateinit var realDisplayMetrics: DisplayMetrics
    private var width = 0
    private var height = 0
    private var shouldWidth = 0
    private var shouldHeight = 0
    private var ratio = 1.0
    private var videoWidth = 0
    private var videoHeight = 0
    private var density = 2.0f
    private var scale = 1.0f
    lateinit var imageHelper: ImageHelper
    private var fullScreenModeListener: (() -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        SP.init(this) // 确保在任何 SP 属性访问之前初始化
        Security.removeProvider("BC")
        Security.addProvider(BouncyCastleProvider())

        displayMetrics = DisplayMetrics()
        realDisplayMetrics = DisplayMetrics()
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        windowManager.defaultDisplay.getRealMetrics(realDisplayMetrics)

        // 获取设备实际分辨率
        if (realDisplayMetrics.heightPixels > realDisplayMetrics.widthPixels) {
            width = realDisplayMetrics.heightPixels
            height = realDisplayMetrics.widthPixels
        } else {
            width = realDisplayMetrics.widthPixels
            height = realDisplayMetrics.heightPixels
        }

        density = Resources.getSystem().displayMetrics.density
        scale = displayMetrics.scaledDensity

        // 在 SP.init 后安全地访问 SP 属性
        updateDisplayMetrics(SP.fullScreenMode)
        SP.compactMenu = true

        initX5()

        Thread.setDefaultUncaughtExceptionHandler(YourTVExceptionHandler(this))

        imageHelper = ImageHelper(this)
    }

    // 更新显示尺寸
    private fun updateDisplayMetrics(isFullScreen: Boolean) {
        // 16:9 模式（原有逻辑）
        if ((width.toDouble() / height) < (16.0 / 9.0)) {
            ratio = width * 2 / 1920.0 / density
            shouldWidth = width
            shouldHeight = (width * 9.0 / 16.0).toInt()
        } else {
            ratio = height * 2 / 1080.0 / density
            shouldHeight = height
            shouldWidth = (height * 16.0 / 9.0).toInt()
        }
        if (isFullScreen) {
            videoWidth = width
            videoHeight = height
        } else {
            videoWidth = shouldWidth
            videoHeight = shouldHeight
        }
    }

    fun setFullScreenModeListener(listener: () -> Unit) {
        fullScreenModeListener = listener
    }

    fun toggleFullScreenMode(isFullScreen: Boolean) {
        SP.fullScreenMode = isFullScreen
        updateDisplayMetrics(isFullScreen)
        Log.d(TAG, "Full screen mode: $isFullScreen, shouldWidth: $shouldWidth, shouldHeight: $shouldHeight")
        fullScreenModeListener?.invoke()
    }

    private fun initX5() {
        val settings = mutableMapOf<String, Any>(
            TbsCoreSettings.TBS_SETTINGS_USE_SPEEDY_CLASSLOADER to true,
            TbsCoreSettings.TBS_SETTINGS_USE_DEXLOADER_SERVICE to true
        )
        QbSdk.initTbsSettings(settings)
        QbSdk.initX5Environment(this, object : QbSdk.PreInitCallback {
            override fun onCoreInitFinished() {
                Log.i(TAG, "X5 Core initialized")
            }

            override fun onViewInitFinished(isX5: Boolean) {
                Log.i(TAG, "X5 View initialized, isX5=$isX5")
                isX5Initialized = isX5
                if (isX5) {
                    Log.d(TAG, "X5 kernel is available on device, no need to download")
                } else {
                    Log.w(TAG, "X5 kernel not available, triggering download")
                }
            }
        })
    }

    fun isX5Available(): Boolean {
        return isX5Initialized
    }

    fun getDisplayMetrics(): DisplayMetrics {
        return displayMetrics
    }

    fun toast(message: CharSequence = "", duration: Int = Toast.LENGTH_SHORT) {
        // 全局 Toast 节流：同一文案 8 秒内最多弹一次，
        // 避免多源后台导入/自动换线时"解析源/下载失败"等提示反复刷屏
        val key = message.toString()
        val now = System.currentTimeMillis()
        val last = toastThrottle[key]
        if (last != null && now - last < TOAST_THROTTLE_MS) {
            Log.d(TAG, "toast throttled: $key")
            return
        }
        toastThrottle[key] = now
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, duration).show()
        }
    }

    private val toastThrottle = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val TOAST_THROTTLE_MS = 8_000L

    fun shouldWidthPx(): Int {
        return shouldWidth
    }

    fun shouldHeightPx(): Int {
        return shouldHeight
    }

    fun videoWidthPx(): Int {
        return videoWidth
    }

    fun videoHeightPx(): Int {
        return videoHeight
    }

    fun dp2Px(dp: Int): Int {
        return (dp * ratio * density + 0.5f).toInt()
    }

    fun px2Px(px: Int): Int {
        return (px * ratio + 0.5f).toInt()
    }

    fun px2PxFont(px: Float): Float {
        return (px * ratio / scale).toFloat()
    }

    fun sp2Px(sp: Float): Float {
        return (sp * ratio * scale).toFloat()
    }

    override fun attachBaseContext(base: Context) {
        try {
            super.attachBaseContext(base)
        } catch (_: Exception) {
            super.attachBaseContext(base)
        }
    }
}
