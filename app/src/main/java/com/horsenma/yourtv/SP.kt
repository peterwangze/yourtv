package com.horsenma.yourtv


import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import com.horsenma.yourtv.data.Global
import com.horsenma.yourtv.data.Source
import com.horsenma.yourtv.data.StableSource
import androidx.core.content.edit
import androidx.lifecycle.MutableLiveData
import android.content.pm.PackageManager
import android.app.UiModeManager
import android.content.res.Configuration
import android.util.Log

object SP {
    private const val TAG = "SP"
    private const val KEY_CHANNEL_REVERSAL = "channel_reversal"
    private const val KEY_CHANNEL_NUM = "channel_num"
    private const val KEY_TIME = "time"
    private const val KEY_BOOT_STARTUP = "boot_startup"
    private const val KEY_POSITION = "position"
    private const val KEY_POSITION_GROUP = "position_group"
    private const val KEY_POSITION_SUB = "position_sub"
    private const val KEY_REPEAT_INFO = "repeat_info"
    private const val KEY_CONFIG_URL = "config"
    private const val KEY_CHANNEL = "channel"
    private const val KEY_DEFAULT_LIKE = "default_like"
    private const val KEY_DISPLAY_SECONDS = "display_seconds"
    private const val KEY_SHOW_ALL_CHANNELS = "show_all_channels"
    private const val KEY_COMPACT_MENU = "compact_menu"
    private const val KEY_LIKE = "like"
    private const val KEY_PROXY = "proxy"
    private const val KEY_EPG = "epg"
    private const val KEY_VERSION = "version"
    private const val KEY_LOG_TIMES = "log_times"
    private const val KEY_SOURCES = "sources"
    private const val KEY_SOFT_DECODE = "soft_decode"
    private const val KEY_AUTO_SWITCH_SOURCE = "auto_switch_source"
    private const val KEY_DEFAULTS_IMPORTED = "defaults_imported"
    private const val KEY_DEFAULTS_LAST_ATTEMPT = "defaults_last_attempt"
    private const val PREF_NAME = "YourTV"
    private const val KEY_STABLE_SOURCES = "stable_sources"
    private const val KEY_SHOW_SOURCE_BUTTON = "show_source_button"
    private const val KEY_ENABLE_SCREEN_OFF_AUDIO = "enable_screen_off_audio"
    private const val KEY_ENABLE_WEBVIEW_TYPE = "enable_webview_type"
    private const val KEY_FULL_SCREEN_MODE = "full_screen_mode"
    private const val RESOLUTION_CACHE_PREFIX = "resolution_"
    private const val RESOLUTION_CACHE_TIMESTAMP_PREFIX = "resolution_timestamp_"
    private const val CACHE_DURATION = 24 * 60 * 60 * 1000L // 24 小时

    // 移除静态常量，改用动态默认值
    private var DEFAULT_SOFT_DECODE: Boolean = false
    internal var DEFAULT_FULL_SCREEN_MODE: Boolean = true

    const val DEFAULT_ENABLE_WEBVIEW_TYPE = false
    const val DEFAULT_ENABLE_SCREEN_OFF_AUDIO = true
    const val DEFAULT_SHOW_SOURCE_BUTTON = true
    const val DEFAULT_AUTO_SWITCH_SOURCE = false
    const val DEFAULT_CHANNEL_REVERSAL = false
    const val DEFAULT_CHANNEL_NUM = false
    const val DEFAULT_TIME = true
    const val DEFAULT_BOOT_STARTUP = false
    const val DEFAULT_CONFIG_URL = ""
    const val DEFAULT_PROXY = ""
    const val DEFAULT_EPG =
        "https://live.fanmingming.cn/e.xml,https://raw.githubusercontent.com/fanmingming/live/main/e.xml"
    const val DEFAULT_CHANNEL = 0
    const val DEFAULT_SHOW_ALL_CHANNELS = false
    const val DEFAULT_COMPACT_MENU = true
    const val DEFAULT_DISPLAY_SECONDS = true
    const val DEFAULT_LOG_TIMES = 10


    // 0 favorite; 1 all
    const val DEFAULT_POSITION_GROUP = 1
    const val DEFAULT_POSITION = 0
    const val DEFAULT_REPEAT_INFO = true
    // 预置公共直播源（首次启动自动导入第一个可用源，其余可在"源管理"中切换）
    var DEFAULT_SOURCES = """
        [{"uri":"https://live.zbds.top/tv/iptv4.txt"},
         {"uri":"https://live.fanmingming.cn/tv/m3u/ipv6.m3u"},
         {"uri":"https://live.fanmingming.com/tv/m3u/ipv6.m3u"},
         {"uri":"https://live.fanmingming.cn/tv/m3u/ipv4.m3u"},
         {"uri":"https://raw.githubusercontent.com/jk2024988/TV2024/main/%E5%92%AA%E5%92%952.m3u"},
         {"uri":"https://raw.githubusercontent.com/hououinkami/AppleTV/main/Source/China_v4.m3u"},
         {"uri":"https://raw.githubusercontent.com/zhmzjj310144/migu-sports/main/%E4%B8%89%E6%BA%90%E5%90%88%E5%B9%B6_%E5%A4%AE%E8%A7%86%E4%BD%93%E8%82%B2%E5%9C%B0%E6%96%B9%E7%BB%BC%E5%90%88%E6%BA%90.m3u"},
         {"uri":"https://raw.githubusercontent.com/hujingguang/ChinaIPTV/main/cnTV1_ALL.m3u8"},
         {"uri":"https://raw.githubusercontent.com/vbskycn/iptv/master/tv/iptv4.m3u"},
         {"uri":"https://raw.githubusercontent.com/vicjl/myIPTV/main/TV-IPV4.m3u"},
         {"uri":"https://iptv-org.github.io/iptv/countries/cn.m3u"},
         {"uri":"https://raw.githubusercontent.com/best-fan/iptv-sources/main/cn_all.m3u8"},
         {"uri":"https://raw.githubusercontent.com/best-fan/iptv-sources/main/cn_province.m3u8"},
         {"uri":"https://raw.githubusercontent.com/best-fan/iptv-sources/main/cn_cctv.m3u8"},
         {"uri":"https://raw.githubusercontent.com/Kimentanm/aptv/master/m3u/iptv.m3u"},
         {"uri":"https://iptv-org.github.io/iptv/languages/zho.m3u"},
         {"uri":"https://iptv-org.github.io/iptv/countries/hk.m3u"},
         {"uri":"https://iptv-org.github.io/iptv/countries/tw.m3u"},
         {"uri":"https://iptv-org.github.io/iptv/countries/mo.m3u"}]
    """.trimIndent()

    fun defaultSourceUrls(): List<String> {
        return try {
            val list: List<Source> = gson.fromJson(DEFAULT_SOURCES, typeSourceList)
            list.map { it.uri }
        } catch (e: Exception) {
            Log.e("SP", "Failed to parse DEFAULT_SOURCES: ${e.message}")
            emptyList()
        }
    }

    private lateinit var sp: SharedPreferences
    private val gson = Global.gson
    private val typeSourceList = Global.typeSourceList
    private val typeStableSourceList = Global.typeStableSourceList
    // 收藏集内存缓存：避免 applyChannelList 等逐频道调用 getStringSet 全量复制
    // （收藏集越大越接近 O(n²) 主线程开销）。所有写入都经过 setLike/deleteLike，
    // 与磁盘保持一致；init 后首次访问懒加载。
    @Volatile
    private var likeCache: Set<String>? = null

    private fun likeSet(): Set<String> {
        likeCache?.let { return it }
        val loaded = sp.getStringSet(KEY_LIKE, emptySet()) ?: emptySet()
        likeCache = loaded
        return loaded
    }

    // 判断设备是否为触摸屏设备
    @SuppressLint("ServiceCast")
    private fun isTouchScreenDevice(context: Context): Boolean {
        val packageManager = context.packageManager
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val isTv = uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        val hasTouchScreen = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        return hasTouchScreen && !isTv
    }

    // 初始化默认值
    private fun initDefaultValues(context: Context) {
        DEFAULT_SOFT_DECODE = isTouchScreenDevice(context) // 触摸屏为 true，电视为 false
        DEFAULT_FULL_SCREEN_MODE = isTouchScreenDevice(context) // 触摸屏为 true，电视为 false
    }

    fun init(context: Context) {
        // 先初始化默认值
        initDefaultValues(context)

        sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        // 初始化 showSourceButton
        if (!sp.contains(KEY_SHOW_SOURCE_BUTTON)) {
            sp.edit(commit = true) { putBoolean(KEY_SHOW_SOURCE_BUTTON, DEFAULT_SHOW_SOURCE_BUTTON) }
        }

        if (!sp.contains(KEY_SOFT_DECODE)) {
            sp.edit(commit = true) { putBoolean(KEY_SOFT_DECODE, DEFAULT_SOFT_DECODE) }
        }

        // 初始化 fullScreenModeLiveData
        fullScreenModeLiveData.postValue(fullScreenMode)
    }

    var enableScreenOffAudio: Boolean
        get() = sp.getBoolean(KEY_ENABLE_SCREEN_OFF_AUDIO, DEFAULT_ENABLE_SCREEN_OFF_AUDIO)
        set(value) = sp.edit() { putBoolean(KEY_ENABLE_SCREEN_OFF_AUDIO, value) }

    var channelReversal: Boolean
        get() = sp.getBoolean(KEY_CHANNEL_REVERSAL, DEFAULT_CHANNEL_REVERSAL)
        set(value) = sp.edit() { putBoolean(KEY_CHANNEL_REVERSAL, value) }

    var channelNum: Boolean
        get() = sp.getBoolean(KEY_CHANNEL_NUM, DEFAULT_CHANNEL_NUM)
        set(value) = sp.edit() { putBoolean(KEY_CHANNEL_NUM, value) }

    var time: Boolean
        get() = sp.getBoolean(KEY_TIME, DEFAULT_TIME)
        set(value) = sp.edit() { putBoolean(KEY_TIME, value) }

    var bootStartup: Boolean
        get() = sp.getBoolean(KEY_BOOT_STARTUP, DEFAULT_BOOT_STARTUP)
        set(value) = sp.edit() { putBoolean(KEY_BOOT_STARTUP, value) }

    var positionGroup: Int
        get() = sp.getInt(KEY_POSITION_GROUP, DEFAULT_POSITION_GROUP)
        set(value) = sp.edit() { putInt(KEY_POSITION_GROUP, value) }

    var position: Int
        get() = sp.getInt(KEY_POSITION, DEFAULT_POSITION)
        set(value) = sp.edit() { putInt(KEY_POSITION, value) }

    var positionSub: Int
        get() = sp.getInt(KEY_POSITION_SUB, 0)
        set(value) = sp.edit() { putInt(KEY_POSITION_SUB, value) }

    var repeatInfo: Boolean
        get() = sp.getBoolean(KEY_REPEAT_INFO, DEFAULT_REPEAT_INFO)
        set(value) = sp.edit() { putBoolean(KEY_REPEAT_INFO, value) }

    var configUrl: String?
        get() = sp.getString(KEY_CONFIG_URL, DEFAULT_CONFIG_URL)
        set(value) = sp.edit() { putString(KEY_CONFIG_URL, value) }

    var channel: Int
        get() = sp.getInt(KEY_CHANNEL, DEFAULT_CHANNEL)
        set(value) = sp.edit() { putInt(KEY_CHANNEL, value) }

    var compactMenu: Boolean
        get() = sp.getBoolean(KEY_COMPACT_MENU, DEFAULT_COMPACT_MENU)
        set(value) = sp.edit() { putBoolean(KEY_COMPACT_MENU, value) }

    var showAllChannels: Boolean
        get() = sp.getBoolean(KEY_SHOW_ALL_CHANNELS, DEFAULT_SHOW_ALL_CHANNELS)
        set(value) = sp.edit() { putBoolean(KEY_SHOW_ALL_CHANNELS, value) }

    var defaultLike: Boolean
        get() = sp.getBoolean(KEY_DEFAULT_LIKE, false)
        set(value) = sp.edit() { putBoolean(KEY_DEFAULT_LIKE, value) }

    var displaySeconds: Boolean
        get() = sp.getBoolean(KEY_DISPLAY_SECONDS, DEFAULT_DISPLAY_SECONDS)
        set(value) = sp.edit() { putBoolean(KEY_DISPLAY_SECONDS, value) }

    var softDecode: Boolean
        get() = sp.getBoolean(KEY_SOFT_DECODE, DEFAULT_SOFT_DECODE)
        set(value) {
            if (sp.getBoolean(KEY_SOFT_DECODE, DEFAULT_SOFT_DECODE) != value) {
                sp.edit(commit = true) { putBoolean(KEY_SOFT_DECODE, value) }
            }
        }

    fun getLike(id: Int): Boolean {
        return likeSet().contains(id.toString())
    }

    fun setLike(id: Int, liked: Boolean) {
        val stringSet = likeSet().toMutableSet()
        if (liked) {
            stringSet.add(id.toString())
        } else {
            stringSet.remove(id.toString())
        }

        sp.edit() { putStringSet(KEY_LIKE, stringSet) }
        likeCache = stringSet
    }

    fun deleteLike() {
        sp.edit() { remove(KEY_LIKE) }
        likeCache = emptySet()
    }

    var proxy: String?
        get() = sp.getString(KEY_PROXY, DEFAULT_PROXY)
        set(value) = sp.edit() { putString(KEY_PROXY, value) }

    var epg: String?
        get() = sp.getString(KEY_EPG, DEFAULT_EPG)
        set(value) = sp.edit() { putString(KEY_EPG, value) }

    var version: String?
        get() = sp.getString(KEY_VERSION, "")
        set(value) = sp.edit() { putString(KEY_VERSION, value) }

    var logTimes: Int
        get() = sp.getInt(KEY_LOG_TIMES, DEFAULT_LOG_TIMES)
        set(value) = sp.edit() { putInt(KEY_LOG_TIMES, value) }

    var sources: String?
        get() = sp.getString(KEY_SOURCES, null) ?: DEFAULT_SOURCES
        set(value) = sp.edit() { putString(KEY_SOURCES, value) }

    var lastDownloadTime: Long
        get() = sp.getLong("lastDownloadTime", 0L)
        set(value) = sp.edit() { putLong("lastDownloadTime", value) }

    var autoSwitchSource: Boolean
        get() = sp.getBoolean(KEY_AUTO_SWITCH_SOURCE, DEFAULT_AUTO_SWITCH_SOURCE)
        set(value) = sp.edit() { putBoolean(KEY_AUTO_SWITCH_SOURCE, value) }

    var stableSources: String?
        get() = sp.getString(KEY_STABLE_SOURCES, null)
        set(value) = sp.edit() { putString(KEY_STABLE_SOURCES, value) }

    var defaultsImported: Boolean
        get() = sp.getBoolean(KEY_DEFAULTS_IMPORTED, false)
        set(value) = sp.edit() { putBoolean(KEY_DEFAULTS_IMPORTED, value) }

    var defaultsLastAttempt: Long
        get() = sp.getLong(KEY_DEFAULTS_LAST_ATTEMPT, 0L)
        set(value) = sp.edit() { putLong(KEY_DEFAULTS_LAST_ATTEMPT, value) }

    var showSourceButton: Boolean
        get() = sp.getBoolean(KEY_SHOW_SOURCE_BUTTON, DEFAULT_SHOW_SOURCE_BUTTON)
        set(value) = sp.edit() { putBoolean(KEY_SHOW_SOURCE_BUTTON, value) }

    var enableWebviewType: Boolean
        get() = sp.getBoolean(KEY_ENABLE_WEBVIEW_TYPE, DEFAULT_ENABLE_WEBVIEW_TYPE)
        set(value) = sp.edit() { putBoolean(KEY_ENABLE_WEBVIEW_TYPE, value) }

    val fullScreenModeLiveData by lazy { MutableLiveData<Boolean>() }

    var fullScreenMode: Boolean
        get() {
            if (!::sp.isInitialized) {
                return DEFAULT_FULL_SCREEN_MODE // 默认值，防止未初始化时崩溃
            }
            return sp.getBoolean(KEY_FULL_SCREEN_MODE, DEFAULT_FULL_SCREEN_MODE)
        }
        set(value) {
            if (::sp.isInitialized) {
                sp.edit().putBoolean(KEY_FULL_SCREEN_MODE, value).apply()
                fullScreenModeLiveData.postValue(value)
            }
        }

    fun getStableSources(): List<StableSource> {
        val json = stableSources ?: return emptyList()
        return try {
            val sources = gson.fromJson(json, typeStableSourceList) as List<StableSource>
            sources.filter {
                System.currentTimeMillis() - it.timestamp < 7 * 24 * 60 * 60 * 1000
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setStableSources(sources: List<StableSource>) {
        val json = gson.toJson(sources, typeStableSourceList)
        stableSources = json
    }

    fun getResolutionCache(url: String): String? {
        val key = RESOLUTION_CACHE_PREFIX + url.hashCode()
        val timestampKey = RESOLUTION_CACHE_TIMESTAMP_PREFIX + url.hashCode()
        val cachedResolution = sp.getString(key, null)
        val timestamp = sp.getLong(timestampKey, 0)
        return if (cachedResolution != null && System.currentTimeMillis() - timestamp < CACHE_DURATION) {
            cachedResolution
        } else {
            null
        }
    }

    fun cacheResolution(url: String, resolution: String) {
        val key = RESOLUTION_CACHE_PREFIX + url.hashCode()
        val timestampKey = RESOLUTION_CACHE_TIMESTAMP_PREFIX + url.hashCode()
        sp.edit().apply {
            putString(key, resolution)
            putLong(timestampKey, System.currentTimeMillis())
            apply()
        }
    }

}
