package com.horsenma.yourtv

import android.content.Context
import android.os.Build
import android.util.Log
import com.horsenma.yourtv.requests.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.system.exitProcess

class YourTVExceptionHandler(val context: Context) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(t: Thread, e: Throwable) {
        val crashInfo =
            "APP: ${context.appVersionName}, PRODUCT: ${Build.PRODUCT}, DEVICE: ${Build.DEVICE}, SUPPORTED_ABIS: ${Build.SUPPORTED_ABIS.joinToString()}, BOARD: ${Build.BOARD}, MANUFACTURER: ${Build.MANUFACTURER}, MODEL: ${Build.MODEL}, VERSION: ${Build.VERSION.SDK_INT}\nThread: ${t.name}\nException: ${e.message}\nStackTrace: ${
                Log.getStackTraceString(
                    e
                )
            }\n"
        // 无论是否限流都打印原始异常，便于定位问题
        Log.e(TAG, "Uncaught exception on thread ${t.name}: $crashInfo")

        // 崩溃后主线程 Looper 已死：如果进程继续存活，界面会黑屏、遥控无响应（假死）。
        // 正确做法：在独立线程保存崩溃日志，然后结束进程，由系统/桌面重启应用。
        val logger = Thread {
            try {
                runBlocking {
                    launch {
                        saveCrashInfoToFile(crashInfo)
                    }
                }
            } catch (ignored: Exception) {
                // 日志上传失败不影响退出
            } finally {
                android.os.Process.killProcess(android.os.Process.myPid())
                exitProcess(2)
            }
        }
        logger.start()
        // 主线程最多等日志线程 3 秒，超时直接自杀兜底，避免假死
        try {
            logger.join(3000)
        } catch (ignored: InterruptedException) {
        }
        android.os.Process.killProcess(android.os.Process.myPid())
        exitProcess(2)
    }

    private suspend fun saveCrashInfoToFile(crashInfo: String) {
        if (isLimit()) {
            Log.e(TAG, crashInfo)
        } else {
            try {
                saveLog(crashInfo)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun isLimit(): Boolean {
        if (context.appVersionName != SP.version) {
            SP.version = context.appVersionName
            SP.logTimes = SP.DEFAULT_LOG_TIMES
            return false
        } else {
            SP.logTimes--
            return SP.logTimes < 0
        }
    }

    private suspend fun saveLog(crashInfo: String) {
        withContext(Dispatchers.IO) {
            try {
                val request = okhttp3.Request.Builder()
                    .url("https://lyrics.run/my-tv-0/v1/log")
                    .method("POST", crashInfo.toRequestBody("text/plain".toMediaType()))
                    .build()

                HttpClient.okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.i(TAG, "log success")
                    } else {
                        Log.e(TAG, "log failed: ${response.codeAlias()}")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    companion object {
        private const val TAG = "YourTVException"
    }
}
