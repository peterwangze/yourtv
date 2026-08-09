package com.horsenma.yourtv


import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toFile
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.horsenma.yourtv.Utils.getDateFormat
import com.horsenma.yourtv.Utils.getUrls
import com.horsenma.yourtv.data.EPG
import com.horsenma.yourtv.data.Global.gson
import com.horsenma.yourtv.data.Global.typeEPGMap
import com.horsenma.yourtv.data.Global.typeTvList
import com.horsenma.yourtv.data.Source
import com.horsenma.yourtv.data.SourceType
import com.horsenma.yourtv.data.TV
import com.horsenma.yourtv.models.EPGXmlParser
import com.horsenma.yourtv.models.ChannelClassifier
import com.horsenma.yourtv.models.ChannelMetadataParser
import com.horsenma.yourtv.models.Sources
import com.horsenma.yourtv.models.TVGroupModel
import com.horsenma.yourtv.models.TVListModel
import com.horsenma.yourtv.models.TVModel
import com.horsenma.yourtv.requests.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.horsenma.yourtv.data.PlayerType
import com.horsenma.yourtv.data.Global
import com.google.gson.reflect.TypeToken
import com.horsenma.yourtv.data.StableSource
import kotlin.math.min


class MainViewModel : ViewModel() {

    private var firstloadcode = false
    private var cachedCodeContent: String? = null
    private var cachedFileContent: String? = null
    private lateinit var context: Context // 存储 Context
    private var defaultsImportStarted = false
    private var lastEpgAttempt = 0L
    private val epgCooldownMs = 30L * 60 * 1000
    // 解析互斥：同一时刻只允许一个解析+应用任务，避免列表被并发整体替换导致画面闪断
    private val parseMutex = Mutex()
    // 全量线路探测只做一次，避免首启占满弱机网络
    @Volatile private var linesProbed = false
    // 会话内只弹一次"解析源"Toast：多个源并行/串行导入时避免 Toast 排队刷屏
    private val parsingToastShown = java.util.concurrent.atomic.AtomicBoolean(false)
    // 多源聚合：apply=false 静默解析时收集各源频道，聚合完成后一次性替换界面列表
    private val aggregateBuffer = mutableListOf<List<TV>>()
    @Volatile private var aggregateStarted = false

    // 解析结果缓存：启动秒出列表，避免每次启动重新解析/下载
    // Bump the parsed-list cache when the metadata parser changes.  Reusing a
    // v2.7 cache would reintroduce the old `tvg-id=...` display names after an
    // upgrade even though all new source files are parsed correctly.
    private val channelsCacheFileName = "channels_list_cache_v290.json"
    private var lastChannelsHash = 0

    private val _playTrigger = MutableLiveData<TVModel?>()
    val playTrigger: LiveData<TVModel?> get() = _playTrigger

    // 添加公共方法来触发播放
    fun triggerPlay(tvModel: TVModel?) {
        _playTrigger.postValue(tvModel)
    }

    private val _currentTvModel = MutableLiveData<TVModel?>()
    val currentTvModel: LiveData<TVModel?> get() = _currentTvModel

    fun setCurrentTvModel(model: TVModel?) {
        _currentTvModel.value = model
    }

    fun storeUsersInfo(usersInfo: List<String>) {
        Log.d(TAG, "Storing users_info: $usersInfo")
    }
    private var timeFormat = if (SP.displaySeconds) "HH:mm:ss" else "HH:mm"

    private lateinit var appDirectory: File
    // 主线程 applyChannelList 写、IO 线程（preloadLogo/probeAllLines/str2Channels）读，
    // @Volatile 保证跨线程可见性，避免解析期间读到半初始化列表
    @Volatile
    var listModel: List<TVModel> = emptyList()
    val groupModel = TVGroupModel()
    private var cacheFile: File? = null
    private var cacheChannels = ""
    private var cacheWebChannels = ""
    private var initialized = false

    private lateinit var cacheEPG: File
    private var epgUrl = SP.epg

    private lateinit var imageHelper: ImageHelper

    val sources = Sources()

    private val _channelsOk = MutableLiveData<Boolean>()
    val channelsOk: LiveData<Boolean>
        get() = _channelsOk

    fun setDisplaySeconds(displaySeconds: Boolean) {
        timeFormat = if (displaySeconds) "HH:mm:ss" else "HH:mm"
        SP.displaySeconds = displaySeconds
    }

    fun setChannelsOk(value: Boolean) {
        _channelsOk.postValue(value)
    }

    fun getTime(): String {
        return getDateFormat(timeFormat)
    }

    fun updateEPG(force: Boolean = false) {
        // 冷却：30 分钟内不重复自动尝试（避免启动时 EPG 镜像疯狂重试刷屏/占网络）；
        // 用户在设置里手动点击"更新节目单"时 force=true 跳过冷却，并给出结果反馈
        if (!force && System.currentTimeMillis() - lastEpgAttempt < epgCooldownMs) {
            return
        }
        lastEpgAttempt = System.currentTimeMillis()
        viewModelScope.launch {
            var success = false
            if (!epgUrl.isNullOrEmpty()) {
                success = updateEPG(epgUrl!!)
            }
            if (!success && !SP.epg.isNullOrEmpty()) {
                success = updateEPG(SP.epg!!)
            }
            if (force) {
                if (success) {
                    R.string.epg_update_success.showToast()
                    Log.i(TAG, "EPG update succeeded (manual)")
                } else {
                    R.string.epg_update_failed.showToast()
                    Log.w(TAG, "EPG update failed (manual)")
                }
            }
        }
    }

    /** 强制刷新当前直播源（跳过 24h 缓存重新下载并应用，用户手动触发） */
    fun refreshActiveSource() {
        val url = SP.configUrl
        if (url.isNullOrEmpty()) {
            R.string.source_refresh_no_source.showToast()
            Log.w(TAG, "refreshActiveSource: no active source configured")
            return
        }
        viewModelScope.launch {
            // 强制刷新激活源后重新聚合多源，避免列表退化成单源
            aggregateRemainingSources(forceRefreshActive = true)
        }
    }

    fun updateConfig() {
        if (!::context.isInitialized) {
            // context 尚未就绪（重进时 ready() 可能早于 init()）：延迟重试
            Log.w(TAG, "updateConfig: context not ready, retry in 3s")
            viewModelScope.launch {
                delay(3_000L)
                updateConfig()
            }
            return
        }
        SP.configUrl?.let {
            if (it.startsWith("http")) {
                // IO 线程执行，避免 prefs/文件 IO 阻塞主线程（重进黑屏根因）
                viewModelScope.launch(Dispatchers.IO) {
                    // 已有合并缓存（含配置源频道）时静默刷新，避免每次启动用单源覆盖多源合并结果
                    importFromUrl(it, "", apply = !channelsCacheFile().exists())
                    updateEPG()
                }
            }
        }
    }

    /**
     * 首次启动时自动导入预置的公共直播源（界面就绪后延迟执行）。
     * 任一预置源导入成功后即停止，其余源保留在源列表中可随时切换；
     * 全部失败时记录时间，24 小时内不再重试，避免每次启动卡顿。
     */
    fun importDefaultsIfNeeded() {
        if (defaultsImportStarted) {
            return
        }
        if (!SP.configUrl.isNullOrEmpty()) {
            Log.d(TAG, "importDefaultsIfNeeded: configUrl already set, skip defaults")
            SP.defaultsImported = true
            return
        }
        if (SP.defaultsImported &&
            System.currentTimeMillis() - SP.defaultsLastAttempt < DEFAULTS_RETRY_INTERVAL_MS
        ) {
            Log.d(TAG, "importDefaultsIfNeeded: last attempt too recent, skip")
            return
        }
        defaultsImportStarted = true
        SP.defaultsLastAttempt = System.currentTimeMillis()
        viewModelScope.launch(Dispatchers.IO) {
            var anySuccess = false
            for (url in SP.defaultSourceUrls()) {
                if (url.isBlank()) continue
                Log.d(TAG, "importDefaultsIfNeeded: trying default source $url")
                try {
                   // 每个源最多等 12 秒：弱网/源失效时快速跳过，避免首屏长时间卡"解析源"
                    withTimeoutOrNull(12_000L) { importFromUrl(url, silent = true) }
                } catch (e: Exception) {
                    Log.e(TAG, "importDefaultsIfNeeded: failed $url: ${e.message}")
                }
                if (SP.configUrl == url) {
                    Log.i(TAG, "importDefaultsIfNeeded: active source set to $url")
                    anySuccess = true
                    break
                }
            }
            // 无论成败都标记已尝试；失败时靠 lastAttempt 控制 24 小时后重试
            SP.defaultsImported = true
            if (anySuccess) {
                Log.i(TAG, "importDefaultsIfNeeded: done, active source = ${SP.configUrl}")
                // 后台静默导入其余源并聚合多线路（不阻塞观看、不弹提示）
                aggregateRemainingSources()
            } else {
                Log.w(TAG, "importDefaultsIfNeeded: all default sources failed, retry after 24h")
                defaultsImportStarted = false
            }
        }
    }

    /**
     * 多源聚合：把剩余默认源静默下载并解析（apply=false，不替换界面列表），
     * 全部完成后按"分类+规范名"合并频道，央视/卫视/地方频道因此获得多条线路，
     * 并按清晰度+稳定度排序后一次性应用。
     */
    private suspend fun aggregateRemainingSources(forceRefreshActive: Boolean = false) {
        if (aggregateStarted) return
        aggregateStarted = true
        // 先把当前激活源也纳入聚合（其独有频道不能丢），再导入其余默认源
        val activeUrl = SP.configUrl
        val urls = buildList {
            if (!activeUrl.isNullOrBlank()) add(activeUrl)
            addAll(SP.defaultSourceUrls().filter { it.isNotBlank() && it != activeUrl })
        }
        Log.i(TAG, "aggregateRemainingSources: importing ${urls.size} sources for multi-source merge")
        coroutineScope {
            urls.map { url ->
                async(Dispatchers.IO) {
                    try {
                       // 单源最多等 50 秒：即使下载内部超时失效也不会拖死整个聚合
                       withTimeoutOrNull(50_000L) {
                            importFromUrl(url, apply = false, forceDownload = forceRefreshActive && url == activeUrl, silent = true)
                       }
                    } catch (e: Exception) {
                        Log.e(TAG, "aggregateRemainingSources: failed $url: ${e.message}")
                    }
                }
            }.awaitAll()
        }
        aggregateAllSources()
    }

    /** 将 aggregateBuffer 中收集的各源频道按分类+规范名合并，线路按清晰度/稳定度排序后应用 */
    private suspend fun aggregateAllSources() {
        if (aggregateBuffer.isEmpty()) {
            Log.w(TAG, "aggregateAllSources: nothing collected, skip")
            return
        }
        Log.i(TAG, "aggregateAllSources: merging ${aggregateBuffer.size} source lists")
        val mergedMap = LinkedHashMap<String, TV>()
        for (list in aggregateBuffer) {
            for (tv in list) {
                val key = com.horsenma.yourtv.models.ChannelClassifier.mergeKey(tv.title, tv.group)
                val existing = mergedMap[key]
                if (existing == null) {
                    mergedMap[key] = tv
                } else if (existing.playerType == tv.playerType) {
                    existing.uris = (existing.uris + tv.uris).distinct()
                    val incomingHeaders = tv.uriHeaders.ifEmpty {
                        tv.uris.associateWith { tv.headers.orEmpty() }
                    }
                    mergedMap[key] = existing.copy(
                        logo = if (existing.logo.isNullOrEmpty()) tv.logo else existing.logo,
                        name = if (existing.name.isNullOrEmpty()) tv.name else existing.name,
                        uriHeaders = existing.uriHeaders + incomingHeaders
                    )
                }
                // 同名但类型不同（IPTV vs WEBVIEW）不合并线路，保留先到者
            }
        }
        val merged = mergedMap.values.toList()
        if (merged.isEmpty()) {
            Log.w(TAG, "aggregateAllSources: merged list empty")
            return
        }
        // 线路排序：频道反向聚合后，先健康度，再清晰度/稳定度/延迟。
        merged.forEach { tv ->
            tv.uris = rankChannelUris(tv)
        }
        Log.i(TAG, "aggregateAllSources: merged ${merged.size} channels")
        // 频道列表按 央视→卫视→地方→海外→其他 稳定排序，默认频道落在央视（CCTV）
        val ordered = merged.sortedWith(
            compareBy(
                {
                    com.horsenma.yourtv.models.ChannelClassifier.rankOfGroup(
                        com.horsenma.yourtv.models.ChannelClassifier.displayGroup(it.title, it.group)
                    )
                },
                { com.horsenma.yourtv.models.ChannelClassifier.channelSortOrder(it.title, it.group) },
                { com.horsenma.yourtv.models.ChannelClassifier.displayGroup(it.title, it.group) },
                { com.horsenma.yourtv.models.ChannelClassifier.normalizeName(it.title) },
                { it.title.lowercase() }
            )
        )
        withContext(Dispatchers.Main) {
            // 首次聚合期间初始内置源的第一项可能是 CCTV4K/CCTV+，
            // 这会让用户以为选择 CCTV1 后跳台。没有稳定播放记录时，
            // 明确以 CCTV1 为默认；已有稳定源则恢复用户上次频道。
            val restoreTitle = groupModel.getCurrentTitle()
                ?.takeIf { SP.getStableSources().isNotEmpty() }
            applyChannelList(ordered, restoreTitle, null)
        }
        saveChannelsCache(ordered)
        aggregateBuffer.clear()
        aggregateStarted = false
    }

    private fun getCache(): String {
        return if (cacheFile!!.exists()) {
            cacheFile!!.readText()
        } else {
            ""
        }
    }

    fun init(context: Context) {
        this.context = context
        val application = context.applicationContext as YourTVApplication
        imageHelper = application.imageHelper

        if (groupModel.getAllList() == null || groupModel.getAllList()!!.tvList.value.isNullOrEmpty()) {
            groupModel.addTVListModel(TVListModel(context.getString(R.string.my_favorites), 0))
            groupModel.addTVListModel(TVListModel(context.getString(R.string.all_channels), 1))
        }

        appDirectory = context.filesDir
        cacheFile = File(appDirectory, CACHE_FILE_NAME)
        try {
            if (!cacheFile!!.exists()) {
                cacheFile!!.createNewFile()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create cache file: ${e.message}", e)
        }

        // Step 1: Immediately play the latest stable source
        viewModelScope.launch(Dispatchers.Main) {
            // 播放稳定源
            val stableSources = SP.getStableSources()
            var defaultChannel: TVModel? = null
            if (stableSources.isNotEmpty()) {
                val selectedSource = stableSources.maxByOrNull { it.timestamp }
                if (selectedSource != null) {
                    val tv = TV(
                        id = selectedSource.id,
                        name = selectedSource.name,
                        title = selectedSource.title,
                        description = selectedSource.description,
                        logo = selectedSource.logo,
                        image = selectedSource.image,
                        uris = selectedSource.uris,
                        videoIndex = selectedSource.videoIndex,
                        headers = selectedSource.headers,
                        group = selectedSource.group,
                        sourceType = SourceType.valueOf(selectedSource.sourceType),
                        number = selectedSource.number,
                        child = selectedSource.child
                    )
                    defaultChannel = TVModel(tv).apply {
                        setLike(SP.getLike(tv.id))
                        setGroupIndex(2)
                        listIndex = 0
                    }

                    // 打印 TV 数据为 JSON
                    val tvJson = Global.gson.toJson(defaultChannel.tv)
                    Log.d(TAG, "Stable source TV JSON: $tvJson")

                    groupModel.setCurrent(defaultChannel)
                    triggerPlay(defaultChannel)
                    Log.i(TAG, "Playing latest stable channel immediately: ${defaultChannel.tv.title}, url: ${defaultChannel.getVideoUrl()}")
                } else {
                    Log.w(TAG, "Selected stable source is null")
                }
            } else {
                Log.w(TAG, "Selected stable source is null")
                try {
                    val inputStream = context.resources.openRawResource(R.raw.rawstablesource)
                    val jsonString = inputStream.bufferedReader().use { it.readText() }
                    val type = object : TypeToken<List<TV>>() {}.type
                    val stableSources: List<TV> = Global.gson.fromJson(jsonString, type)
                    if (stableSources.isNotEmpty()) {
                        val tv = stableSources.random()
                        val defaultChannel = TVModel(tv).apply {
                            setLike(SP.getLike(tv.id))
                            setGroupIndex(2)
                            listIndex = 0
                        }
                        groupModel.setCurrent(defaultChannel)
                        triggerPlay(defaultChannel)
                        Log.i(TAG, "Playing random fallback stable channel from raw: ${defaultChannel.tv.title}, url: ${defaultChannel.getVideoUrl()}")
                    } else {
                        Log.w(TAG, "No stable sources found in rawstablesource.txt")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load random stable source from rawstablesource.txt: ${e.message}", e)
                }
            }

        }

        // Step 2: 1 秒后后台加载频道列表（缓存/激活源/内置源）。
        // 解析在 IO 线程，应用切主线程；channelsOk 在列表真正应用完成后才置位，
        // MainActivity 据此触发首频道起播，避免首屏卡顿与自动起播竞态。
        viewModelScope.launch(Dispatchers.IO) {
            delay(1_000L)
            var channelsLoaded = false

            val cachedChannels = loadChannelsCache()
            if (!cachedChannels.isNullOrEmpty()) {
                applyChannelList(cachedChannels, null) {
                    _channelsOk.value = true
                }
                channelsLoaded = true
                Log.d(TAG, "Channels loaded from channels_list_cache: ${cachedChannels.size}")
            }

            if (!channelsLoaded) {
                val filename = context.getSharedPreferences("SourceCache", Context.MODE_PRIVATE).getString("active_source", null)
                if (filename != null) {
                    // 激活源（缓存内容变化时替换列表，不变则跳过）
                    if (loadActiveSource()) {
                        channelsLoaded = true
                        Log.d(TAG, "Channels loaded from active_source")
                    }
                }
            }

            if (!channelsLoaded && cacheFile!!.exists()) {
                try {
                    cachedFileContent = cachedFileContent ?: cacheFile!!.readText()
                    if (cachedFileContent!!.isNotEmpty()) {
                        if (parseAndApply(cachedFileContent!!, cacheFile, "", "")) {
                            Log.d(TAG, "Channels loaded from cacheFile")
                            channelsLoaded = true
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load cacheFile: ${e.message}", e)
                }
            }
            if (!channelsLoaded) {
                try {
                    cacheChannels = context.resources.openRawResource(DEFAULT_CHANNELS_FILE).bufferedReader().use { it.readText() }
                    if (cacheChannels.isNotEmpty()) {
                        if (parseAndApply(cacheChannels, null, "", "")) {
                            Log.d(TAG, "Channels loaded from /raw/channels.txt")
                            channelsLoaded = true
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load /raw/channels.txt: ${e.message}", e)
                }
            }

            if (!channelsLoaded) {
                try {
                    cacheWebChannels = context.resources.openRawResource(DEFAULT_WEBCHANNELS_FILE).bufferedReader().use { it.readText() }
                    if (cacheWebChannels.isNotEmpty()) {
                        if (parseAndApply(cacheWebChannels, null, "", "")) {
                            Log.d(TAG, "Web channels loaded from /raw/webchannelsiniptv")
                        }
                    } else {
                        Log.w(TAG, "Web channels file is empty: /raw/webchannelsiniptv")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load /raw/webchannelsiniptv: ${e.message}", e)
                }
            }

            initialized = true
            _channelsOk.postValue(channelsLoaded)

            // 界面就绪后再后台导入预置源，避免首屏卡顿
            importDefaultsIfNeeded()
        }

        viewModelScope.launch(Dispatchers.IO) {
            cacheEPG = File(appDirectory, CACHE_EPG)
            try {
                if (!cacheEPG.exists()) {
                    cacheEPG.createNewFile()
                } else if (readEPG(cacheEPG.readText())) {
                    Log.i(TAG, "cacheEPG success")
                } else {
                    Log.i(TAG, "cacheEPG failure")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle EPG cache: ${e.message}", e)
            }
        }

    }

    suspend fun preloadLogo() {
        if (!this::imageHelper.isInitialized) {
            return
        }

        withContext(Dispatchers.IO) { // 添加后台线程调度
            // 首启降载：列表 bind 时已按需加载 logo，这里只预热前 PRELOAD_LOGO_LIMIT 个频道
            for (tvModel in listModel.take(PRELOAD_LOGO_LIMIT)) {
                var name = tvModel.tv.name
                if (name.isEmpty()) {
                    name = tvModel.tv.title
                }
                val url = tvModel.tv.logo
                var urls =
                    listOf(
                        "https://live.fanmingming.cn/tv/$name.png"
                    ) + getUrls("https://raw.githubusercontent.com/fanmingming/live/main/tv/$name.png")
                if (url.isNotEmpty()) {
                    urls = (getUrls(url) + urls).distinct()
                }

                imageHelper.preloadImage(name, urls)
            }
        }
    }

    suspend fun readEPG(input: InputStream): Boolean = withContext(Dispatchers.IO) {
        try {
            val res = EPGXmlParser().parse(input)

            withContext(Dispatchers.Main) {
                val e1 = mutableMapOf<String, List<EPG>>()
                for (m in listModel) {
                    val name = m.tv.name.ifEmpty { m.tv.title }.lowercase()
                    if (name.isEmpty()) {
                        continue
                    }

                    for ((n, epg) in res) {
                        if (name.contains(n, ignoreCase = true)) {
                            m.setEpg(epg)
                            e1[name] = epg
                            break
                        }
                    }
                }
                cacheEPG.writeText(gson.toJson(e1))
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun readEPG(str: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val res: Map<String, List<EPG>> = gson.fromJson(str, typeEPGMap)

            withContext(Dispatchers.Main) {
                for (m in listModel) {
                    val name = m.tv.name.ifEmpty { m.tv.title }.lowercase()
                    if (name.isEmpty()) {
                        continue
                    }

                    val epg = res[name]
                    if (epg != null) {
                        m.setEpg(epg)
                    }
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun updateEPG(url: String): Boolean {
        val urls = url.split(",").flatMap { u -> getUrls(u) }

        var success = false
        for (a in urls) {
            withContext(Dispatchers.IO) {
                try {
                    val request = okhttp3.Request.Builder().url(a).build()
                    val response = HttpClient.okHttpClient.newCall(request).execute()

                    if (response.isSuccessful) {
                        response.bodyAlias()?.byteStream()?.use { stream ->
                            if (readEPG(stream)) {
                                success = true
                            }
                        } ?: run {
                            Log.e(TAG, "EPG $a response body is null")
                        }
                    } else {
                        Log.e(TAG, "EPG $a ${response.codeAlias()}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "EPG $a error")
                }
            }

            if (success) {
                break
            }
        }

        return success
    }

   suspend fun importFromUrl(
       url: String,
       id: String = "",
       skipHistory: Boolean = false,
       forceDownload: Boolean = false,
        apply: Boolean = true,
        silent: Boolean = false
   ) {
       Log.d(TAG, "importFromUrl: url=$url, id=$id, skipHistory=$skipHistory, forceDownload=$forceDownload")
       if (url.isBlank()) {
           Log.w(TAG, "importFromUrl: Skipping empty URL")
            if (!silent) R.string.sources_download_error.showToast()
           return
       }

        //val filename = if (id.isNotBlank()) id else url.substringAfterLast("/").takeIf { it.isNotBlank() } ?: "source_${url.hashCode()}.txt"
        val rawFilename = url.substringAfterLast("/").takeIf { it.isNotBlank() }?.substringBeforeLast(".") ?: "source_${url.hashCode()}"
        val filename = "$rawFilename.txt"
        val prefs = context.getSharedPreferences("SourceCache", Context.MODE_PRIVATE)
        val cacheTimeKey = "cache_time_$filename"
        val urlKey = "url_$filename"
        val cacheTime = prefs.getLong(cacheTimeKey, 0)
        val cacheDuration = 24 * 60 * 60 * 1000
        val cacheCodeFile = File(appDirectory, "cache_$filename")
        val MAX_CACHE_FILES = 20
        // 缓存内容存文件（SharedPreferences 只存元数据，避免大字符串拖慢主线程）
        val cachedContent = if (cacheCodeFile.exists()) {
            try {
                cacheCodeFile.readText()
            } catch (e: Exception) {
                Log.e(TAG, "importFromUrl: Failed to read cache file $filename: ${e.message}")
                null
            }
        } else {
            null
        }

        // 按文件清理缓存（最多保留 MAX_CACHE_FILES 个源）
        val cacheFiles = appDirectory.listFiles { f ->
            f.isFile && f.name.startsWith("cache_") && f.name != CACHE_FILE_NAME && f.name != CACHE_EPG
        }?.sortedBy { it.lastModified() } ?: emptyList()
        if (cacheFiles.size >= MAX_CACHE_FILES) {
            val oldest = cacheFiles.firstOrNull()
            if (oldest != null) {
                Log.d(TAG, "Deleting oldest cache file: ${oldest.name}")
                oldest.delete()
                val oldFilename = oldest.name.removePrefix("cache_")
                with(prefs.edit()) {
                    remove("cache_time_$oldFilename")
                    remove("url_$oldFilename")
                    apply()
                }
            }
        }

        // 检查缓存，并更新时间戳
        if (!forceDownload && cachedContent != null && cacheCodeFile.exists() ) {
            Log.d(TAG, "importFromUrl: Using cached content for filename=$filename, cacheTime=$cacheTime")
            viewModelScope.launch(Dispatchers.IO) {
                with(prefs.edit()) {
                    putLong("cache_time_$filename", System.currentTimeMillis())
                    // 仅真正应用（apply=true，首源导入/手动切源）时才移动 active_source；
                    // 聚合静默导入（apply=false）不得覆盖激活源，避免并发竞态把激活源指向随机源
                    if (apply) putString("active_source", filename)
                    apply()
                }
            }
            // 在 IO 线程解析频道列表（大文件解析不再占用主线程），并等待解析完成
            withContext(Dispatchers.IO) {
                val isHex = cachedContent.trim().matches(Regex("^[0-9a-fA-F]+$"))
                val contentToParse = if (isHex) {
                    SourceDecoder.decodeHexSource(cachedContent) ?: cachedContent
                } else {
                    cachedContent
                }
                parseAndApplyChannels(contentToParse, cacheCodeFile, if (skipHistory) "" else url, id, apply)
            }
            return
        }

        // 下载
        Log.d(TAG, "importFromUrl: Download filename=$filename")
        Log.d(TAG, "importFromUrl: Download url=$url")
        val result = withContext(Dispatchers.IO) {
            DownGithubPrivate.download(context, url, id)
        }
        when {
            result.isSuccess -> {
                val content = result.getOrNull() ?: ""
               if (content.isEmpty()) {
                   Log.w(TAG, "importFromUrl: Downloaded empty content for url=$url")
                    if (!silent) R.string.sources_download_error.showToast()
                   return
               }
                val isHex = content.trim().matches(Regex("^[0-9a-fA-F]+$"))
                val normalizedContent = if (isHex) {
                    SourceDecoder.decodeHexSource(content) ?: content
                } else {
                    content.replace("\r\n", "\n").replace("\r", "\n")
                }
                val contentToCache = if (isHex) content else SourceEncoder.encodeJsonSource(normalizedContent)
                withContext(Dispatchers.IO) {
                    try {
                        cacheCodeFile.writeText(contentToCache)
                        Log.d(TAG, "importFromUrl: Wrote cache_$filename for filename=$filename, content length=${contentToCache.length}")
                    } catch (e: Exception) {
                        Log.e(TAG, "importFromUrl: Failed to write cache_$filename: ${e.message}")
                    }
                }
                withContext(Dispatchers.IO) {
                    parseAndApplyChannels(normalizedContent, cacheCodeFile, if (skipHistory) "" else url, id, apply)
                    SP.lastDownloadTime = System.currentTimeMillis()
                    with(prefs.edit()) {
                        putLong(cacheTimeKey, System.currentTimeMillis())
                        putString(urlKey, url)
                        if (apply) putString("active_source", filename)
                        apply()
                    }
                    Log.d(TAG, "importFromUrl: Cached content for filename=$filename, isHex=$isHex")
                }
            }
           result.isFailure -> {
               Log.e(TAG, "importFromUrl: Download failed for url=$url: ${result.exceptionOrNull()?.message}")
                if (!silent) R.string.sources_download_error.showToast()
           }
       }
   }

    fun reset(context: Context) {
        val filename = "default_channels.txt"
        val defaultUrl = "default://channels"
        val prefs = context.getSharedPreferences("SourceCache", Context.MODE_PRIVATE)

        val str = try {
            context.resources.openRawResource(R.raw.channels).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "reset: Failed to read R.raw.channels: ${e.message}")
            R.string.channel_read_error.showToast()
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            parseMutex.withLock {
                try {
                    with(prefs.edit()) {
                        putString("active_source", filename)
                        putString("url_$filename", defaultUrl)
                        apply()
                    }
                    str2Channels(str) {
                        withContext(Dispatchers.Main) { _channelsOk.value = true }
                    }
                    Log.d(TAG, "reset: Processed default channels from R.raw.channels")
                    _channelsOk.postValue(true)
                } catch (e: Exception) {
                    Log.e(TAG, "reset: Failed to process default channels: ${e.message}")
                    R.string.channel_read_error.showToast()
                }
            }
        }
    }

    fun importFromUri(uri: Uri, id: String = "") {
        if (uri.scheme == "file") {
            val file = uri.toFile()
            Log.i(TAG, "file $file")
            val str = if (file.exists()) {
                file.readText()
            } else {
                R.string.file_not_exist.showToast()
                return
            }
            tryStr2Channels(str, file, uri.toString(), id)
        } else {
            viewModelScope.launch {
                importFromUrl(uri.toString(), id = id)
                Log.d(TAG, "SP.sources after importFromUri: ${SP.sources}")
                // 通知 init 重新加载
                if (listModel.isNotEmpty()) {
                    _channelsOk.value = true
                }
            }
        }
    }

    /**
     * 将国内直播源常用的 TXT 格式转换为 M3U 格式：
     *   分组名,#genre#
     *   频道名,http://...
     * 复用现有 M3U 解析管线（多线路合并、分组、EPG 等）。
     */
    private fun convertTxtToM3U(str: String): String {
        val sb = StringBuilder("#EXTM3U\n")
        var group = ""
        for (rawLine in str.split("\n", "\r\n", "\r")) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#EXTM3U")) continue
            if (line.endsWith("#genre#")) {
                group = line.substringBeforeLast(',').trim()
                continue
            }
            val comma = line.indexOf(',')
            if (comma <= 0) continue
            val name = line.substring(0, comma).trim()
            val url = line.substring(comma + 1).trim()
            if (name.isEmpty() || url.isEmpty()) continue
            if (!url.startsWith("http://") && !url.startsWith("https://") &&
                !url.startsWith("rtmp://") && !url.startsWith("rtsp://") &&
                !url.startsWith("webview://")
            ) {
                continue
            }
            val attrs = StringBuilder()
            if (group.isNotEmpty()) {
                attrs.append(" group-title=\"").append(group).append("\"")
            }
            attrs.append(" tvg-name=\"").append(name).append("\"")
            sb.append("#EXTINF:-1").append(attrs).append(",").append(name).append("\n")
            sb.append(url).append("\n")
        }
        return sb.toString()
    }

    /**
     * 解析并应用频道列表。解析在 IO 线程执行（不再阻塞主线程），多个解析任务通过
     * parseMutex 串行化，避免列表被并发整体替换导致画面闪断。onApplied 在列表真正
     * 应用完成（或确认列表未变）后于主线程回调。
     */
    fun tryStr2Channels(
        str: String,
        file: File?,
        url: String,
        id: String = "",
        apply: Boolean = true,
        onApplied: (suspend () -> Unit)? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            parseAndApplyChannels(str, file, url, id, apply, onApplied)
        }
    }

    /**
     * 同步解析+应用（IO 线程调用，内部持 parseMutex）。apply=false 时仅解析并收集
     * 频道到 aggregateBuffer，不替换界面列表（多源聚合用，避免观看中列表被反复整体替换）。
     */
    private suspend fun parseAndApplyChannels(
        str: String,
        file: File?,
        url: String,
        id: String = "",
        apply: Boolean = true,
        onApplied: (suspend () -> Unit)? = null
    ): Boolean {
        return parseMutex.withLock {
            try {
                if (str.isEmpty()) {
                    Log.w(TAG, "Input string is empty for url=$url")
                    R.string.channel_read_error.showToast()
                    return@withLock false
                }
                val isPlainText = str.trim().startsWith("#EXTM3U") ||
                        str.trim().startsWith("http://") ||
                        str.trim().startsWith("https://") ||
                        str.contains("#genre#")
                val isHex = str.trim().matches(Regex("^[0-9a-fA-F]+$"))
                val targetFile = file ?: cacheFile
                Log.d(TAG, "parseAndApplyChannels: Input str length=${str.length}, isPlainText=$isPlainText, isHex=$isHex, url=$url, apply=$apply")
                val ok = str2Channels(str, apply) {
                    withContext(Dispatchers.Main) {
                        _channelsOk.value = true
                    }
                    onApplied?.invoke()
                }
                if (ok) {
                    if (isPlainText) {
                        val encryptedStr = SourceEncoder.encodeJsonSource(str)
                        if (targetFile != null) {
                            // 同步写缓存：避免聚合/二次启动读到半截文件（WRONG_FINAL_BLOCK_LENGTH 根因）
                            targetFile.writeText(encryptedStr)
                        }
                        cacheChannels = str
                    } else if (isHex) {
                        if (targetFile != null) {
                            targetFile.writeText(str)
                        }
                        val decryptedStr = SourceDecoder.decodeHexSource(str) ?: str
                        cacheChannels = decryptedStr
                    } else {
                        try {
                            val decodedStr = SourceDecoder.decodeHexSource(str) ?: str
                            val encryptedStr = SourceEncoder.encodeJsonSource(decodedStr)
                            if (targetFile != null) {
                                targetFile.writeText(encryptedStr)
                            }
                            cacheChannels = decodedStr
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to process non-plaintext, non-hex content: ${e.message}")
                            cacheChannels = str
                        }
                    }
                    if (url.isNotEmpty()) {
                        // 仅 apply 模式更新激活源指针；聚合（apply=false）导入不移动激活源
                        if (apply) com.horsenma.yourtv.SP.configUrl = url
                        val source = Source(id = id, uri = url)
                        viewModelScope.launch(Dispatchers.Main) {
                            sources.addSource(source)
                            Log.d(TAG, "parseAndApplyChannels: Added source: $source")
                        }
                    }
                } else {
                    Log.w(TAG, "str2Channels failed for url=$url")
                    // 解析失败说明缓存可能损坏：删除文件，下次重新下载
                    if (file != null && file.exists()) {
                        Log.w(TAG, "Deleting corrupt cache file: ${file.absolutePath}")
                        file.delete()
                    }
                    R.string.channel_import_error.showToast()
                }
                ok
            } catch (e: Exception) {
                Log.e(TAG, "parseAndApplyChannels: Failed for url=$url: ${e.message}", e)
                if (file != null && file.exists()) {
                    Log.w(TAG, "Deleting corrupt cache file after exception: ${file.absolutePath}")
                    file.delete()
                }
                R.string.channel_read_error.showToast()
                false
            }
        }
    }

    /**
     * 启动链路的同步解析+应用：IO 线程调用，解析完成后等待 applyChannelList 在主线程
     * 应用完毕（或确认列表未变），随后置位 channelsOk 并返回是否成功。
     */
    private suspend fun parseAndApply(str: String, file: File?, url: String, id: String = ""): Boolean {
        if (str.isEmpty()) return false
        return parseMutex.withLock {
            val applied = CompletableDeferred<Boolean>()
            val ok = str2Channels(str) { applied.complete(true) }
            if (!ok) {
                if (file != null && file.exists()) {
                    Log.w(TAG, "parseAndApply: deleting corrupt cache file: ${file.absolutePath}")
                    file.delete()
                }
                return@withLock false
            }
            withTimeoutOrNull(30_000) { applied.await() } ?: run {
                Log.e(TAG, "parseAndApply: apply timed out for url=$url")
            }
            _channelsOk.postValue(true)
            true
        }
    }

    private fun str2Channels(
        str: String,
        apply: Boolean = true,
        onApplied: (suspend () -> Unit)? = null
    ): Boolean {
        if (apply && initialized && str == cacheChannels) {
            Log.w(TAG, "same channels, skipping parsing")
            return true
        }

        if (str.isEmpty()) {
            Log.w(TAG, "Input string is empty")
            return false
        }

        if (parsingToastShown.compareAndSet(false, true)) {
            R.string.parsing_live_source.showToast()
        }

        var string = str
        val isPlainText = str.trim().startsWith("#EXTM3U") ||
                str.trim().startsWith("http://") ||
                str.trim().startsWith("https://") ||
                str.contains("#genre#")
        val isHex = str.trim().matches(Regex("^[0-9a-fA-F]+$"))

        Log.d(TAG, "str2Channels: isPlainText=$isPlainText, isHex=$isHex, str length=${str.length}")

        try {
            if (isHex) {
                string = SourceDecoder.decodeHexSource(str) ?: str
                Log.d(TAG, "str2Channels: Decoded HEX, new string length=${string.length}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode string: ${e.message}")
        }

        if (string.isEmpty()) {
            Log.w(TAG, "channels is empty after processing")
            return false
        }

        // 兼容 TXT(#genre#) 直播源格式
        if (!string.trim().startsWith("#EXTM3U") && string.contains("#genre#")) {
            Log.d(TAG, "str2Channels: Detected TXT(#genre#) format, converting to M3U")
            string = convertTxtToM3U(string)
        }

        // 只读获取当前频道标题：getCurrent() 会写入 LiveData，只能在主线程调用；
        // 解析链路现在运行在 IO 线程，这里使用只读版本避免跨线程崩溃
        val currentTvTitle = groupModel.getCurrentTitle()
        Log.d(TAG, "str2Channels: Saving currentTvTitle=$currentTvTitle")

        // 分流：提取 webview:// 地址
        val lines = string.split("\n", "\r\n", "\r").filter { it.isNotBlank() }
        val webviewTVs = mutableListOf<com.horsenma.mytv1.data.TV>()
        val iptvLines = mutableListOf<String>()
        var currentTV: com.horsenma.mytv1.data.TV? = null

        for (line in lines) {
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty()) continue

            if (trimmedLine.startsWith("#EXTM3U")) {
                iptvLines.add(trimmedLine)
                val epgIndex = trimmedLine.indexOf("x-tvg-url=\"")
                if (epgIndex != -1) {
                    val endIndex = trimmedLine.indexOf("\"", epgIndex + 11)
                    if (endIndex != -1) epgUrl = trimmedLine.substring(epgIndex + 11, endIndex)
                }
            } else if (trimmedLine.startsWith("#EXTINF", ignoreCase = true)) {
                val parsed = com.horsenma.yourtv.models.ChannelMetadataParser.parse(trimmedLine)
                currentTV = parsed?.let {
                    iptvLines.add(trimmedLine)
                    com.horsenma.mytv1.data.TV(
                        title = it.title,
                        name = it.name,
                        logo = it.logo,
                        group = it.group,
                        uris = emptyList(),
                        block = null
                    )
                }
                if (parsed == null) {
                    // Public feeds contain many expected VOD/ad entries; keep
                    // them out of logcat so a real playback error remains visible.
                    Log.d(TAG, "Ignoring malformed/noise #EXTINF line")
                }
            } else if (trimmedLine.startsWith("#EXTVLCOPT:http-", ignoreCase = true)) {
                // Preserve per-channel HTTP headers for the strict IPTV parser below.
                iptvLines.add(trimmedLine)
            } else if (trimmedLine.startsWith("webview://") && currentTV != null) {
                val url = trimmedLine.removePrefix("webview://")
                val domain = Uri.parse(url).host ?: ""
                // 读取 webview_loading_blacklist.json（缓存结果）
                val blacklistMap: Map<String, List<String>> by lazy {
                    try {
                        val jsonText = context.assets.open("webview_loading_blacklist.json").bufferedReader().use { it.readText() }
                        Global.gson.fromJson(jsonText, object : TypeToken<Map<String, List<String>>>() {}.type)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to read webview_loading_blacklist.json: ${e.message}")
                        emptyMap()
                    }
                }
                // 从 Global.blockMap 或 blacklistMap 获取屏蔽列表
                val blockList = Global.blockMap[currentTV.group]
                    ?: blacklistMap.entries.find { it.key == domain || domain.endsWith(".${it.key}") }?.value
                    ?: listOf("ad.js", "banner.css")
                currentTV = currentTV.copy(
                    uris = listOf(url),
                    block = blockList,
                    id = url.hashCode(),
                    started = "document.querySelector('.floatNav').style.display = 'none'",
                    script = "", // 移除脚本设置，依赖 WebFragment 的 scriptMap
                    selector = "",
                    finished = ""
                )
                webviewTVs.add(currentTV)
                currentTV = null
            } else if (!trimmedLine.startsWith("#") && currentTV != null) {
                iptvLines.add(trimmedLine)
                currentTV = null
            }
        }

        // 处理 WebView 直播源
        val webviewModels = mutableListOf<TVModel>()
        if (webviewTVs.isNotEmpty()) {
            try {
                Log.d(TAG, "str2Channels: Found ${webviewTVs.size} WebView channels")
                // 按 group + name 去重 WebView 频道
                val webviewMap = mutableMapOf<String, MutableList<com.horsenma.mytv1.data.TV>>()
                for (tv in webviewTVs) {
                    val key = (tv.group.orEmpty() + tv.name.orEmpty()).ifEmpty { tv.title.orEmpty() }
                    webviewMap.computeIfAbsent(key) { mutableListOf() }.add(tv)
                }
                webviewModels.addAll(webviewMap.values.mapIndexed { index, tvs ->
                    val uris = tvs.flatMap { it.uris }.distinct()
                    TVModel(
                        com.horsenma.yourtv.data.TV(
                            id = tvs[0].id ?: -1,
                            name = tvs[0].name.orEmpty(),
                            title = tvs[0].title.orEmpty(),
                            logo = tvs[0].logo.orEmpty(),
                            uris = uris,
                            group = tvs[0].group.orEmpty(),
                            playerType = PlayerType.WEBVIEW,
                            block = tvs[0].block.orEmpty(),
                            script = tvs[0].script.orEmpty(),
                            selector = tvs[0].selector.orEmpty(),
                            started = tvs[0].started.orEmpty(),
                            finished = tvs[0].finished.orEmpty(),
                            headers = emptyMap(), // 避免 headers 类型不匹配
                            description = null,
                            image = null,
                            videoIndex = 0,
                            sourceType = SourceType.UNKNOWN,
                            number = -1, // 统一设置为 -1，与原逻辑一致
                            child = emptyList()
                        )
                    ).apply {
                        setLike(SP.getLike(tvs[0].id ?: -1))
                        setGroupIndex(2)
                        listIndex = index
                    }
                })
                Log.d(TAG, "str2Channels: Parsed ${webviewModels.size} WebView channels")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse WebView channels: ${e.message}")
            }
        }

        // 处理 IPTV 直播源
        val iptvList: List<TV> = if (iptvLines.isNotEmpty()) {
            val iptvContent = iptvLines.joinToString("\n")
            when {
                iptvContent.startsWith("[") -> {
                    try {
                        gson.fromJson(iptvContent, typeTvList) ?: emptyList()
                    } catch (e: Exception) {
                        Log.e(TAG, "IPTV JSON parsing failed: ${e.message}")
                        emptyList()
                    }
                }
                iptvContent.startsWith("#") -> {
                    val tvMap = linkedMapOf<String, TV>()
                    var currentTV: TV? = null

                    fun flushCurrent() {
                        val tv = currentTV ?: return
                        if (tv.uris.isEmpty()) return
                        val key = ChannelClassifier.mergeKey(tv.title, tv.group)
                        val previous = tvMap[key]
                        tvMap[key] = if (previous == null) {
                            tv.copy(uriHeaders = tv.uris.associateWith { tv.headers.orEmpty() })
                        } else {
                            previous.copy(
                                uris = (previous.uris + tv.uris).distinct(),
                                logo = previous.logo.ifBlank { tv.logo },
                                headers = previous.headers?.takeIf { it.isNotEmpty() } ?: tv.headers,
                                uriHeaders = previous.uriHeaders + tv.uris.associateWith { tv.headers.orEmpty() }
                            )
                        }
                    }

                    for (line in iptvLines) {
                        val trimmedLine = line.trim()
                        if (trimmedLine.isEmpty()) continue

                        if (trimmedLine.startsWith("#EXTM3U", ignoreCase = true)) {
                            continue
                        } else if (trimmedLine.startsWith("#EXTINF", ignoreCase = true)) {
                            flushCurrent()
                            val parsed = ChannelMetadataParser.parse(trimmedLine)
                            currentTV = parsed?.let {
                                TV(
                                    name = it.name,
                                    title = it.title,
                                    logo = it.logo,
                                    group = it.group,
                                    number = it.number
                                )
                            }
                        } else if (trimmedLine.startsWith("#EXTVLCOPT:http-", ignoreCase = true)) {
                            if (currentTV != null) {
                                val keyValue = trimmedLine.substringAfter("#EXTVLCOPT:http-").split("=", limit = 2)
                                if (keyValue.size == 2) {
                                    currentTV = currentTV.copy(
                                        headers = (currentTV.headers ?: emptyMap()).toMutableMap().apply {
                                            this[keyValue[0]] = keyValue[1]
                                        }
                                    )
                                }
                            }
                        } else if (!trimmedLine.startsWith("#") && currentTV != null) {
                            if (ChannelMetadataParser.isNoiseUri(trimmedLine) ||
                                ChannelMetadataParser.isLikelyWrongChannelUri(currentTV.title, trimmedLine) ||
                                ChannelMetadataParser.isNoise(currentTV.title, currentTV.group, trimmedLine)
                            ) {
                                continue
                            }
                            currentTV = currentTV.copy(
                                uris = currentTV.uris.toMutableList().apply { add(trimmedLine) }
                            )
                        }
                    }
                    flushCurrent()

                    tvMap.values.map { tv ->
                        TV(
                            id = -1,
                            name = tv.name,
                            title = tv.title,
                            description = null,
                            logo = tv.logo,
                            image = null,
                            uris = tv.uris.distinct(),
                            videoIndex = 0,
                            headers = tv.headers,
                            uriHeaders = tv.uriHeaders,
                            group = tv.group,
                            sourceType = SourceType.UNKNOWN,
                            number = tv.number,
                            child = emptyList(),
                            playerType = PlayerType.IPTV
                        )
                    }.filter { it.uris.isNotEmpty() }
                }
                else -> emptyList()
            }
        } else {
            emptyList()
        }

        // 频道级反向关联：每个频道独立汇总线路，质量高/健康的线路排在前面。
        iptvList.forEach { tv ->
            tv.uris = rankChannelUris(tv)
        }

        if (iptvList.isEmpty() && webviewModels.isEmpty()) {
            Log.w(TAG, "str2Channels: Parsed TV list is empty")
            return false
        }

        // 合并 IPTV 和 WebView 频道
        val allTvs = iptvList + webviewModels.map { it.tv }
        val newHash = allTvs.hashCode()
        if (listModel.isNotEmpty() && newHash == lastChannelsHash) {
            Log.d(TAG, "str2Channels: List unchanged, skip UI update")
            if (!apply) {
                // 聚合模式：即使内容与当前列表相同也要收集，避免激活源频道丢失
                aggregateBuffer.add(allTvs)
            }
            if (onApplied != null) {
                viewModelScope.launch(Dispatchers.Main) { onApplied() }
            }
            return true
        }
        if (apply) {
            lastChannelsHash = newHash
            applyChannelList(allTvs, currentTvTitle, onApplied)
            saveChannelsCache(allTvs)
            R.string.live_source_parsed.showToast()
        } else {
            // 多源聚合：只收集解析结果，不替换当前界面列表、不弹提示
            aggregateBuffer.add(allTvs)
            Log.d(TAG, "str2Channels: collected ${allTvs.size} channels for aggregation")
        }

        return true
    }

    /**
     * Rank all URLs belonging to one logical channel.  Stable sources are
     * matched by the canonical channel key rather than TV.id (the latter is
     * -1 while a source is being parsed and used to make every channel share
     * one unrelated stable URL).  Dead lines stay visible for manual recovery,
     * but never win automatic playback.
     */
    private fun rankChannelUris(tv: TV): List<String> {
        val stableUrls = SP.getStableSources()
            .filter { ChannelClassifier.mergeKey(it.title, it.group) == ChannelClassifier.mergeKey(tv.title, tv.group) }
            .flatMap { it.uris }
            .toSet()
        return tv.uris.distinct().sortedWith(
            compareByDescending<String> { if (LineHealth.isDead(it)) 0 else 1 }
                .thenByDescending { if (it in stableUrls) 1 else 0 }
                .thenByDescending { SourceQuality.scoreWithResolution(it, SP.getResolutionCache(it), tv.title) }
                .thenBy { LineHealth.latency(it) ?: Long.MAX_VALUE }
                .thenBy { it }
        )
    }

    fun clearCacheChannels() {
        cacheChannels = ""
        Log.d(TAG, "clearCacheChannels: Cache cleared")
    }

    private fun channelsCacheFile(): File = File(appDirectory, channelsCacheFileName)

    /** 保存解析后的频道列表（本地缓存，启动秒出） */
    private fun saveChannelsCache(tvs: List<TV>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                channelsCacheFile().writeText(gson.toJson(tvs))
                Log.d(TAG, "saveChannelsCache: saved ${tvs.size} channels")
            } catch (e: Exception) {
                Log.e(TAG, "saveChannelsCache failed: ${e.message}")
            }
        }
    }

    /** 读取本地解析缓存 */
    private fun loadChannelsCache(): List<TV>? {
        return try {
            val f = channelsCacheFile()
            if (!f.exists()) return null
            val list: List<TV> = gson.fromJson(f.readText(), typeTvList)
            list.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.e(TAG, "loadChannelsCache failed: ${e.message}")
            null
        }
    }

    /** 将频道列表应用到界面（分组构建 + 默认频道恢复） */
    private fun applyChannelList(tvs: List<TV>, restoreTitle: String?, onApplied: (suspend () -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.Main) {
            // 先捕获旧收藏（聚合后对象会被整体替换，收藏映射需要旧对象信息）
            val oldFavorites = groupModel.getFavoritesList()?.tvList?.value.orEmpty()
            groupModel.setTVListModelList(
                listOf(
                    TVListModel(context.getString(R.string.my_favorites), 0),
                    TVListModel(context.getString(R.string.all_channels), 1)
                )
            )
            // 纯计算（模型构建/合并/排序/分组）移到后台线程：上千频道 × 数十次
            // 关键词匹配在主线程执行会阻塞输入分发导致 ANR（聚合窗口内按任意键必现）
            val (listModelNew, groupMap) = withContext(Dispatchers.Default) {
                buildChannelModel(tvs)
            }
            Log.d(TAG, "applyChannelList: groups=" + groupMap.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value.size}" })
            // 分组固定顺序：央视 → 卫视 → 地方(省份) → 海外(国家) → 其他
            com.horsenma.yourtv.models.ChannelClassifier.sortGroups(groupMap.keys).forEach { group ->
                val tvModels = groupMap[group] ?: return@forEach
                val existingGroup = groupModel.tvGroupValue.find { it.getName() == group }
                if (existingGroup != null) {
                    existingGroup.setTVListModel(tvModels)
                } else {
                    val newGroup = TVListModel(group, groupModel.tvGroupValue.size)
                    newGroup.setTVListModel(tvModels)
                    groupModel.addTVListModel(newGroup)
                }
            }
            listModel = listModelNew
            groupModel.tvGroupValue[1].setTVListModel(listModelNew)

            // 收藏列表随聚合重建：旧收藏对象按 分类+规范名 映射到新频道，
            // 避免收藏停留在过期对象上导致"播放与侧边栏不一致"
            val newFavorites = oldFavorites.mapNotNull { old ->
                val key = com.horsenma.yourtv.models.ChannelClassifier.mergeKey(old.tv.title, old.tv.group)
                listModelNew.firstOrNull {
                    com.horsenma.yourtv.models.ChannelClassifier.mergeKey(it.tv.title, it.tv.group) == key
                }
            }.distinctBy { it.tv.id }
            groupModel.getFavoritesList()?.setTVListModel(newFavorites)

            // 恢复或设置默认频道：用"分类+规范名"把当前频道重新锚定到新列表。
            // 旧实现按 id 匹配稳定源，聚合/刷新后 id 重排会导致匹配失败，
            // 侧边栏被重置到 CCTV1 而实际播放不变——"观看与侧边栏不一致"的根因之一。
            val current = groupModel.getCurrent()
            val currentKey = current?.let {
                com.horsenma.yourtv.models.ChannelClassifier.mergeKey(it.tv.title, it.tv.group)
            }
            val anchored = currentKey?.let { key ->
                listModelNew.firstOrNull {
                    com.horsenma.yourtv.models.ChannelClassifier.mergeKey(it.tv.title, it.tv.group) == key
                }
            }
            val target = when {
                anchored != null -> anchored
                restoreTitle != null -> listModelNew.firstOrNull { it.tv.title == restoreTitle }
                else -> null
            }
            if (target != null) {
                if (target.tv.id != current?.tv?.id) {
                    groupModel.setCurrent(target)
                    Log.d(TAG, "applyChannelList: Re-anchored current to: ${target.tv.title}")
                }
            } else if (listModelNew.isNotEmpty()) {
                val preferred = listModelNew.firstOrNull {
                    com.horsenma.yourtv.models.ChannelClassifier.mergeKey(it.tv.title, it.tv.group) == "央视||cctv1"
                } ?: listModelNew[0]
                groupModel.setCurrent(preferred)
                Log.d(TAG, "applyChannelList: Set default current to: ${preferred.tv.title}")
            }

            viewModelScope.launch(Dispatchers.IO) { preloadLogo() }
            Log.d(TAG, "applyChannelList: Updated listModel size=${listModel.size}")
            groupModel.setChange()
            // 列表就绪后后台探测线路健康
            probeAllLines()
            // 列表应用完成：通知等待方（channelsOk 置位、自动起播等）
            onApplied?.invoke()
        }
    }

    /**
     * 纯计算：构建频道模型、跨源合并、确定性排序与分组。
     * 必须在后台线程执行（ChannelClassifier 分类为密集字符串匹配）。
     * 返回 Pair(排序后的全部频道, 分组名 → 频道列表)。
     */
    private suspend fun buildChannelModel(
        tvs: List<TV>
    ): Pair<MutableList<TVModel>, Map<String, MutableList<TVModel>>> {
        val iptvModels = tvs.mapIndexed { index, tv ->
            val canonicalTitle = ChannelClassifier.displayName(tv.title)
            // 频道 id 用"分类+规范名"哈希而非列表下标：聚合/刷新后顺序变化
            // 也不会让收藏、稳定源、当前频道对不上号（收藏丢失/侧边栏漂移根因）
            val stableId = ChannelClassifier.mergeKey(tv.title, tv.group).hashCode()
            TVModel(tv.copy(id = stableId, title = canonicalTitle, name = canonicalTitle)).apply {
                setLike(SP.getLike(stableId))
                setGroupIndex(2)
                listIndex = index
            }
        }
        val modelMap = mutableMapOf<String, TVModel>()
        iptvModels.forEach { tvModel ->
            val key = com.horsenma.yourtv.models.ChannelClassifier.mergeKey(tvModel.tv.title, tvModel.tv.group)
            val existing = modelMap[key]
            if (existing != null && existing.tv.playerType == tvModel.tv.playerType) {
                modelMap[key]?.tv?.uris = (modelMap[key]?.tv?.uris.orEmpty() + tvModel.tv.uris).distinct()
            } else if (existing == null) {
                modelMap[key] = tvModel
            }
            // 同名但类型不同（IPTV vs WEBVIEW）：不合并线路，保留先到者。
            // 避免 webview:// 地址混入 IPTV 线路导致"播放失败/黑屏"
        }
        // Cached lists are intentionally accepted for instant startup, but
        // they must use the same deterministic order as a fresh aggregate.
        // Otherwise a v2.7-era source order puts CCTV10 before CCTV3 until
        // the next full refresh completes.
        val listModelNew = modelMap.values.sortedWith(
            compareBy<TVModel>(
                { ChannelClassifier.rankOfGroup(ChannelClassifier.displayGroup(it.tv.title, it.tv.group)) },
                { ChannelClassifier.channelSortOrder(it.tv.title, it.tv.group) },
                { ChannelClassifier.displayGroup(it.tv.title, it.tv.group) },
                { ChannelClassifier.normalizeName(it.tv.title) },
                { it.tv.title.lowercase() },
                { it.listIndex }
            )
        ).toMutableList()
        val groupMap = mutableMapOf<String, MutableList<TVModel>>()
        listModelNew.forEach { tvModel ->
            val group = com.horsenma.yourtv.models.ChannelClassifier
                .displayGroup(tvModel.tv.title, tvModel.tv.group)
                .ifEmpty { context.getString(R.string.unknown) }
            groupMap.computeIfAbsent(group) { mutableListOf() }.add(tvModel)
        }
        return listModelNew to groupMap
    }

    /**
     * 后台渐进探测所有频道首选线路（并发 4，每线 2s 超时），
     * 探测结果供切台跳过不可达线路。
     */
    fun probeAllLines() {
        // 全量探测只做一次，避免首启占满弱机网络；后续线路健康由播放失败动态标记
        if (linesProbed) return
        linesProbed = true
        // 主线程读取当前分组名：getCurrent() 会写入 LiveData，只能在主线程调用
        val currentGroup = groupModel.getCurrent()?.tv?.group
        viewModelScope.launch(Dispatchers.IO) {
            // 首帧优先：等当前频道稳定起播后再全量探测，
            // 避免 200 路并发探测抢占带宽导致启动/切台更慢
            delay(10_000L)
            val channels = listModel.toList()
            if (channels.isEmpty()) return@launch
            // 首启降载：只探测当前分组；无分组匹配时探测前 PROBE_CHANNEL_LIMIT 个；WebView 源跳过
            val limited = channels
                .filter { it.tv.playerType != PlayerType.WEBVIEW }
                .let { list -> list.filter { it.tv.group == currentGroup }.ifEmpty { list.take(PROBE_CHANNEL_LIMIT) } }
            if (limited.isEmpty()) return@launch
            val workers = 4
            val chunk = (limited.size + workers - 1) / workers
            (0 until workers).map { w ->
                launch {
                    for (i in (w * chunk) until min((w + 1) * chunk, limited.size)) {
                        val url = limited[i].tv.uris.firstOrNull() ?: continue
                        if (LineHealth.isProbed(url)) continue
                        val probeStart = System.currentTimeMillis()
                        val ok = try {
                            val request = okhttp3.Request.Builder().url(url)
                                .header("Range", "bytes=0-0")
                                .header("User-Agent", "VLC/3.0.18")
                                .build()
                            HttpClient.okHttpClient.newCall(request).execute().use { response ->
                                if (response.isSuccessful) {
                                    // 读取部分数据确认流可播（连接通但无数据=坏线）
                                    response.bodyAlias()?.byteStream()?.use { it.read(ByteArray(1024)) != -1 } ?: false
                                } else {
                                    response.code == 416 // Range 超出=服务器正常
                                }
                            }
                        } catch (e: Exception) {
                            false
                        }
                        LineHealth.mark(url, ok, System.currentTimeMillis() - probeStart)
                    }
                }
            }.forEach { it.join() }
            val deadCount = limited.count { c ->
                c.tv.uris.firstOrNull()?.let { LineHealth.isDead(it) } == true
            }
            Log.d(TAG, "probeAllLines: probed ${limited.size} channels, dead=${deadCount}")
        }
    }

    /** 针对性探测单个频道的全部线路（切台时调用，结果供后续切换/自动换线使用） */
    fun probeChannelLines(tvModel: TVModel) {
        val uris = tvModel.tv.uris
        if (uris.isEmpty()) return
        if (uris.all { LineHealth.isProbed(it) }) return
        viewModelScope.launch(Dispatchers.IO) {
            uris.forEach { url ->
                if (LineHealth.isProbed(url)) return@forEach
                val probeStart = System.currentTimeMillis()
                val ok = try {
                    val request = okhttp3.Request.Builder().url(url)
                        .header("Range", "bytes=0-0")
                        .header("User-Agent", "VLC/3.0.18")
                        .build()
                    HttpClient.okHttpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            response.bodyAlias()?.byteStream()?.use { it.read(ByteArray(1024)) != -1 } ?: false
                        } else {
                            response.code == 416
                        }
                    }
                } catch (e: Exception) {
                    false
                }
                LineHealth.mark(url, ok, System.currentTimeMillis() - probeStart)
            }
        }
    }

    /**
     * 加载激活源缓存（须在 IO 线程调用）。成功应用返回 true。
     */
    suspend fun loadActiveSource(): Boolean {
        val prefs = context.getSharedPreferences("SourceCache", Context.MODE_PRIVATE)
        val filename = prefs.getString("active_source", null) ?: return false
        val cacheTimeKey = "cache_time_$filename"
        val cacheTime = prefs.getLong(cacheTimeKey, 0L)
        val cacheFile = File(context.filesDir, "cache_$filename")
        val cacheDuration = 24 * 60 * 60 * 1000L

        if (filename == "default_channels.txt" || filename == "webchannelsiniptv.txt") {
            val resourceId = if (filename == "default_channels.txt") R.raw.channels else R.raw.webchannelsiniptv
            Log.d(TAG, "loadActiveSource: Loading $filename from R.raw")
            val str = try {
                context.resources.openRawResource(resourceId).bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                Log.e(TAG, "loadActiveSource: Failed to read R.raw.$filename: ${e.message}")
                prefs.edit().remove("active_source").apply()
                return false
            }
            return parseAndApply(str, null, "", filename)
        }

        if (cacheFile.exists() && System.currentTimeMillis() - cacheTime < cacheDuration) {
            Log.d(TAG, "loadActiveSource: Loading active source $filename")
            with(prefs.edit()) {
                putLong(cacheTimeKey, System.currentTimeMillis())
                apply()
            }
            val cachedContent = try {
                cacheFile.readText()
            } catch (e: Exception) {
                Log.e(TAG, "loadActiveSource: Failed to read cache file $filename: ${e.message}")
                null
            }
            if (cachedContent == null) {
                cacheFile.delete()
                with(prefs.edit()) {
                    remove(cacheTimeKey)
                    remove("url_$filename")
                    remove("active_source")
                    apply()
                }
                return false
            }
            val contentToParse = try {
                val isHex = cachedContent.trim().matches(Regex("^[0-9a-fA-F]+$"))
                if (isHex) {
                    SourceDecoder.decodeHexSource(cachedContent) ?: cachedContent
                } else {
                    cachedContent
                }
            } catch (e: Exception) {
                // 缓存损坏（如强退导致半截文件）：清理后回退内置源
                Log.e(TAG, "loadActiveSource: cache decode failed for $filename: ${e.message}")
                cacheFile.delete()
                with(prefs.edit()) {
                    remove(cacheTimeKey)
                    remove("url_$filename")
                    remove("active_source")
                    apply()
                }
                return false
            }
            return parseAndApply(contentToParse, cacheFile, "", filename)
        }
        return false
    }

    fun deleteCacheByTestCode(userId: String) {
        val testCodes = UserInfoManager.getTestCodes()
        val sourceName = testCodes[userId] ?: return
        val filename = "${sourceName}.txt"
        val prefs = context.getSharedPreferences("SourceCache", Context.MODE_PRIVATE)
        val cacheFile = File(appDirectory, "cache_$filename")

        viewModelScope.launch(Dispatchers.IO) {
            if (cacheFile.exists()) {
                cacheFile.delete()
                Log.d(TAG, "Deleted cache file: cache_$filename for test code: $userId")
            }
            with(prefs.edit()) {
                remove("cache_$filename")
                remove("cache_time_$filename")
                remove("url_$filename")
                if (prefs.getString("active_source", null) == filename) {
                    remove("active_source")
                    Log.d(TAG, "Cleared active_source as it matched expired test code's filename: $filename")
                    // 切换到默认源
                    withContext(Dispatchers.Main) {
                        reset(context)
                    }
                }
                apply()
            }
            Log.d(TAG, "Cleared cache entries for test code: $userId, filename: $filename")
            // 通知 UI 更新
            withContext(Dispatchers.Main) {
                context.getString(R.string.test_code_expired, userId).showToast()
                _channelsOk.value = true
            }
        }
    }

    companion object {
        private const val TAG = "MainViewModel"
        const val CACHE_FILE_NAME = "codechannels.txt"
        const val CACHE_EPG = "epg.xml"
        private const val DEFAULTS_RETRY_INTERVAL_MS = 24L * 3600 * 1000
        val DEFAULT_CHANNELS_FILE = R.raw.channels
        val DEFAULT_WEBCHANNELS_FILE = R.raw.webchannelsiniptv
        // 首启降载：全量线路探测只探前 N 个频道，logo 预热只预热前 M 个频道
        private const val PROBE_CHANNEL_LIMIT = 200
        private const val PRELOAD_LOGO_LIMIT = 30
    }
}
