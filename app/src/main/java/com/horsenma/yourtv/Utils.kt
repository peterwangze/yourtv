package com.horsenma.yourtv

import android.content.res.Resources
import android.util.Log
import android.util.TypedValue
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.horsenma.yourtv.ISP.CHINA_MOBILE
import com.horsenma.yourtv.ISP.CHINA_TELECOM
import com.horsenma.yourtv.ISP.CHINA_UNICOM
import com.horsenma.yourtv.ISP.UNKNOWN
import com.horsenma.yourtv.data.Global.gson
import com.horsenma.yourtv.requests.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.cancel


enum class ISP {
    UNKNOWN,
    CHINA_MOBILE,
    CHINA_UNICOM,
    CHINA_TELECOM,
    IPV6,
}

data class IpInfo(
    val ip: String,
    val location: Location
)

data class Location(
    val city_name: String,
    val country_name: String,
    val isp_domain: String,
    val latitude: String,
    val longitude: String,
    val owner_domain: String,
    val region_name: String,
)


object Utils {
    const val TAG = "Utils"

    private var between: Long = 0

    private val _isp = MutableLiveData<ISP>()
    val isp: LiveData<ISP>
        get() = _isp

    fun getDateFormat(format: String): String {
        return SimpleDateFormat(format, Locale.getDefault())
            .format(Date(System.currentTimeMillis() - between))
    }

    fun getDateFormat(format: String, seconds: Int): String {
        return SimpleDateFormat(format, Locale.getDefault())
            .format(Date(seconds * 1000L))
    }

    fun getDateTimestamp(): Long {
        return (System.currentTimeMillis() - between) / 1000
    }

    init {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val currentTimeMillis = getTimestampFromServer()
                Log.i(TAG, "currentTimeMillis $currentTimeMillis")
                if (currentTimeMillis > 0) {
                    between = System.currentTimeMillis() - currentTimeMillis
                }
            } catch (e: Exception) {
                Log.e(TAG, "init", e)
            }

//            try {
//                withContext(Dispatchers.Main) {
//                    _isp.value = getISP()
//                }
//            } catch (e: Exception) {
//                e.printStackTrace()
//            }
        }
    }

    private suspend fun getTimestampFromServer(): Long {
        return withContext(Dispatchers.IO) {
            try {
                val request = okhttp3.Request.Builder()
                    .url("https://ip.ddnspod.com/timestamp")
                    .build()

                HttpClient.okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext 0
                    response.bodyAlias()?.string()?.toLong() ?: 0
                }
            } catch (e: Exception) {
                Log.e(TAG, "getTimestampFromServer", e)
                0
            }
        }
    }

    private suspend fun getISP(): ISP {
        return withContext(Dispatchers.IO) {
            try {
                val request = okhttp3.Request.Builder()
                    .url("https://api.myip.la/json")
                    .build()

                HttpClient.okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext UNKNOWN
                    val string = response.bodyAlias()?.string()
                    val isp = gson.fromJson(string, IpInfo::class.java).location.isp_domain
                    when (isp) {
                        "ChinaMobile" -> CHINA_MOBILE
                        "ChinaUnicom" -> CHINA_UNICOM
                        "ChinaTelecom" -> CHINA_TELECOM
                        else -> UNKNOWN
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "getISP", e)
                UNKNOWN
            }
        }
    }

    fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp, Resources.getSystem().displayMetrics
        ).toInt()
    }

    fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), Resources.getSystem().displayMetrics
        ).toInt()
    }

    fun formatUrl(url: String): String {
        if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file://") || url.startsWith(
                "socks://"
            ) || url.startsWith("socks5://")
        ) {
            return url
        }

        if (url.startsWith("//")) {
            return "http:$url"
        }

        return "http://$url"
    }

    fun getUrls(url: String): List<String> {
        return if (url.startsWith("https://raw.githubusercontent.com") || url.startsWith("https://github.com")) {
            listOf(
                // 2026-08-09 实测（67 组探测）可用镜像，按耗时排序；
                // 原 11 个镜像中 7 个已失效（SSL EOF/证书过期/假 200/429/封禁）已移除
                "https://gh-proxy.com/",
                "https://github.horsenma.top/",
                "https://gh.llkk.cc/",
                "https://ghfast.top/",
                "https://ghproxy.net/",
            ).map { "$it$url" } + listOf(url) // 直连 raw 作最后兜底（国内通常不可达但无害）
        } else {
            listOf(url)
        }
    }

    // 位置：com.horsenma.yourtv.utils 或独立工具类
    object ViewModelUtils {
        fun cancelViewModelJobs(viewModel: ViewModel) {
            try {
                val scopeField = viewModel::class.java.getDeclaredField("viewModelScope")
                scopeField.isAccessible = true
                val scope = scopeField.get(viewModel) as? CoroutineScope
                scope?.cancel()
                Log.d("ViewModelUtils", "ViewModel jobs canceled")
            } catch (e: NoSuchFieldException) {
                Log.w("ViewModelUtils", "viewModelScope not found, skipping cancellation")
            } catch (e: Exception) {
                Log.e("ViewModelUtils", "Failed to cancel ViewModel jobs: ${e.message}", e)
            }
        }
    }
}
