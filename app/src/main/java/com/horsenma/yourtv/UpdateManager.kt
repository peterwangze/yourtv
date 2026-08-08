package com.horsenma.yourtv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import com.google.gson.JsonParseException
import com.horsenma.yourtv.data.Global.gson
import com.horsenma.yourtv.data.ReleaseResponse
import com.horsenma.yourtv.requests.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import android.provider.Settings
import android.widget.ProgressBar
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.widget.Button
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import android.content.pm.PackageManager
import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.provider.MediaStore
import kotlinx.coroutines.*



class UpdateManager(
    private val activity: FragmentActivity,
    private val versionCode: Long
) : ConfirmationFragment.ConfirmationListener {

    var release: ReleaseResponse? = null
    private val context: Context = activity
    private val sharedPrefs: SharedPreferences = activity.getSharedPreferences("UpdatePrefs", Context.MODE_PRIVATE)
    private val TAG = "UpdateManager"
    internal suspend fun getRelease(): ReleaseResponse? {
        val urls = Utils.getUrls(VERSION_URL).toMutableList().apply { add(VERSION_URL) }

        // 检查缓存
        val cacheTime = sharedPrefs.getLong("cached_release_time", 0)
        val cachedResponse = if (System.currentTimeMillis() - cacheTime < 24 * 3600_000) {
            sharedPrefs.getString("cached_release", null)?.let {
                try {
                    gson.fromJson(it, ReleaseResponse::class.java)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse cached release: ${e.message}")
                    null
                }
            }
        } else {
            null
        }
        if (cachedResponse != null) {
            Log.i(TAG, "Using cached release: version=${cachedResponse.version_name}")
            return cachedResponse
        }

        // 分批请求
        val sharedPrefs = context.getSharedPreferences("UpdatePrefs", Context.MODE_PRIVATE)
        val proxyPrefix = sharedPrefs.getString("successful_proxy_prefix", null)
        if (proxyPrefix != null && proxyPrefix != "proxy") {
            val proxyUrl = "$proxyPrefix/$VERSION_URL"
            urls.add(0, proxyUrl)
            Log.i(TAG, "Prioritizing cached proxy URL: $proxyUrl")
        }

        for (batch in urls.chunked(3)) {
            Log.d(TAG, "Processing batch: $batch")
            val results = batch.map { url ->
                CoroutineScope(Dispatchers.IO).async {
                    try {
                        val request = Request.Builder().url(url).build()
                        val response = HttpClient.okHttpClient.newCall(request).execute()
                        if (response.isSuccessful) {
                            response.body?.let {
                                val body = it.string()
                                val trimmedBody = body.trim()
                                if (trimmedBody.isNotEmpty() && (trimmedBody.startsWith("{") || trimmedBody.startsWith("["))) {
                                    try {
                                        val release = gson.fromJson(body, ReleaseResponse::class.java)
                                        return@async release to url
                                    } catch (e: JsonParseException) {
                                        Log.e(TAG, "Gson parse error for $url: ${e.message}")
                                    }
                                } else {
                                    Log.e(TAG, "Invalid JSON for $url: ${body.take(100)}")
                                }
                            }
                        } else {
                            Log.e(TAG, "Request failed for $url with code ${response.code}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Request error for $url: ${e.message}")
                    }
                    null
                }
            }
            try {
                val firstSuccess = withTimeout(3_000) {
                    results.forEach { deferred ->
                        val result = deferred.await()
                        if (result != null) return@withTimeout result
                    }
                    null
                }
                if (firstSuccess != null) {
                    val (release, url) = firstSuccess
                    val newProxyPrefix = url.substringBefore("/https://")
                    sharedPrefs.edit()
                        .putString("cached_release", gson.toJson(release))
                        .putLong("cached_release_time", System.currentTimeMillis())
                        .putString("successful_proxy_prefix", newProxyPrefix)
                        .commit()
                    Log.i(TAG, "Fetched release: version=${release.version_name}, proxy=$newProxyPrefix")
                    results.forEach { it.cancel() }
                    return release
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Batch timed out after 3 seconds")
                results.forEach { it.cancel() }
            }
        }

        // 所有批次失败，使用代理下载
        Log.e(TAG, "All version URL requests failed, attempting proxy download")
        val result = DownGithubPrivate.download(activity, VERSION_URL)
        if (result.isSuccess) {
            val body = result.getOrThrow()
            try {
                val release = gson.fromJson(body, ReleaseResponse::class.java)
                sharedPrefs.edit()
                    .putString("cached_release", body)
                    .putLong("cached_release_time", System.currentTimeMillis())
                    .putString("successful_proxy_prefix", "proxy")
                    .commit()
                Log.i(TAG, "Fetched release via proxy: version=${release.version_name}")
                return release
            } catch (e: JsonParseException) {
                Log.e(TAG, "Gson parse error for proxy response: ${e.message}, body: ${body.take(100)}")
            }
        }
        Log.e(TAG, "Proxy download failed")
        return null
    }

    fun checkAndUpdate(isAutoCheck: Boolean = false) {
        Log.i(TAG, "checkAndUpdate, isAutoCheck=$isAutoCheck")
        CoroutineScope(Dispatchers.Main).launch {
            // 手动检查时显示加载对话框
            var loadingDialog: AlertDialog? = null
            if (!isAutoCheck) {
                loadingDialog = AlertDialog.Builder(activity)
                    .setMessage(activity.getString(R.string.checking_version))
                    .setCancelable(false)
                    .create()
                try {
                    loadingDialog.show()
                    loadingDialog.window?.let {
                        val params = it.attributes
                        params.width = (activity.resources.displayMetrics.widthPixels / 3)
                        params.gravity = android.view.Gravity.CENTER
                        it.attributes = params
                        it.setBackgroundDrawableResource(android.R.color.transparent)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to show loading dialog: ${e.message}")
                    loadingDialog?.dismiss()
                }
            }

            try {
                release = getRelease() // 网络请求
                val versionCodeFromRelease = release?.version_code
                val versionNameFromRelease = release?.version_name
                // 关闭加载对话框
                loadingDialog?.dismiss()

                Log.i(TAG, "versionCodeFromRelease:$versionCodeFromRelease, versionCode:$versionCode ")
                if (versionCodeFromRelease != null && versionNameFromRelease != null) {
                    if (versionCodeFromRelease > versionCode) {
                        Log.i(TAG, "versionCodeFromRelease:$versionCodeFromRelease, versionCode:$versionCode ")
                        // 有新版本，显示更新提示
                        updateUI(
                            text = activity.getString(R.string.latest_version, versionNameFromRelease),
                            update = true,
                            rightButtonAction = { showInstallOptions() },
                            defaultFocusOnLeft = false
                        )
                    } else if (!isAutoCheck) {
                        // 手动检查且无新版本，显示“已是最新版本”
                        updateUI(
                            text = activity.getString(R.string.already_latest),
                            update = false,
                            leftButtonText = "",
                            rightButtonText = activity.getString(R.string.confirm),
                            rightButtonAction = { /* 关闭对话框 */ },
                            defaultFocusOnLeft = false
                        )
                    } else {
                        // 自动检查且无新版本，静默结束
                        Log.i(TAG, "No new version available, versionCode=$versionCode, remote=$versionCodeFromRelease")
                    }
                } else if (!isAutoCheck) {
                    // 手动检查且版本信息无效，显示错误提示
                    updateUI(
                        text = activity.getString(R.string.invalid_version_info),
                        update = false,
                        leftButtonText = "",
                        rightButtonText = activity.getString(R.string.confirm),
                        rightButtonAction = { /* 关闭对话框 */ },
                        defaultFocusOnLeft = false
                    )
                } else {
                    // 自动检查且版本信息无效，静默结束
                    Log.e(TAG, "Invalid release data")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error occurred during version check: ${e.message}")
                // 关闭加载对话框
                loadingDialog?.dismiss()
                if (!isAutoCheck) {
                    // 手动检查且检查失败，显示错误提示
                    updateUI(
                        text = activity.getString(R.string.version_fetch_failed),
                        update = false,
                        leftButtonText = "",
                        rightButtonText = activity.getString(R.string.confirm),
                        rightButtonAction = { /* 关闭对话框 */ },
                        defaultFocusOnLeft = false
                    )
                }
                // 自动检查失败，静默结束
            }
        }
    }

    private fun showInstallOptions() {
        updateUI(
            text = activity.getString(R.string.manual_install_text),
            update = true,
            leftButtonText = activity.getString(R.string.update_direct),
            rightButtonText = activity.getString(R.string.update_manual),
            leftButtonAction = {
                release?.let { startDownload(it, isDirectUpdate = true) }
            },
            rightButtonAction = {
                release?.let { startDownload(it, isDirectUpdate = false) }
            },
            defaultFocusOnLeft = true
        )
    }

    private fun updateUI(
        text: String,
        update: Boolean,
        leftButtonText: String = activity.getString(R.string.cancel),
        rightButtonText: String = activity.getString(R.string.update),
        leftButtonAction: (() -> Unit)? = null,
        rightButtonAction: (() -> Unit)? = null,
        defaultFocusOnLeft: Boolean = true
    ) {
        activity.runOnUiThread {
            try {
                // 隐藏 SettingFragment
                activity.supportFragmentManager.fragments
                    .filterIsInstance<SettingFragment>()
                    .firstOrNull()?.let { settingFragment ->
                        activity.supportFragmentManager.beginTransaction()
                            .hide(settingFragment)
                            .commitAllowingStateLoss()
                        Log.i(TAG, "Hid SettingFragment")
                    }

                // 创建自定义按钮布局（左右排列）
                val buttonLayout = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER
                    setPadding(20, 0, 20, 10)
                }

                // 创建左按钮（原“取消”）
                val leftButton = Button(activity).apply {
                    setText(leftButtonText)
                    setTextColor(Color.WHITE)
                    paint.isFakeBoldText = true
                    setPadding(20, 10, 20, 10)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(20, 0, 0, 0)
                    }
                    val background = GradientDrawable().apply {
                        setColor(Color.parseColor("#80000000"))
                        cornerRadius = 8f
                        setStroke(2, Color.parseColor("#FFFFFF"))
                    }
                    setBackgroundDrawable(background)
                }

                // 创建右按钮（原“更新”）
                val rightButton = Button(activity).apply {
                    setText(rightButtonText)
                    setTextColor(Color.WHITE)
                    paint.isFakeBoldText = true
                    setPadding(20, 10, 20, 10)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(0, 0, 20, 0)
                    }
                    val background = GradientDrawable().apply {
                        setColor(Color.parseColor("#80000000"))
                        cornerRadius = 8f
                        setStroke(2, Color.parseColor("#FFFFFF"))
                    }
                    setBackgroundDrawable(background)
                }

                // 设置焦点变化监听器
                rightButton.setOnFocusChangeListener { _, hasFocus ->
                    val background = GradientDrawable().apply {
                        setColor(if (hasFocus) Color.parseColor("#FF1E90FF") else Color.parseColor("#80000000"))
                        cornerRadius = 8f
                        setStroke(if (hasFocus) 4 else 2, Color.parseColor(if (hasFocus) "#FF1E90FF" else "#FFFFFF"))
                    }
                    rightButton.setBackgroundDrawable(background)
                    if (hasFocus) {
                        leftButton.setBackgroundDrawable(GradientDrawable().apply {
                            setColor(Color.parseColor("#80000000"))
                            cornerRadius = 8f
                            setStroke(2, Color.parseColor("#FFFFFF"))
                        })
                    }
                }

                leftButton.setOnFocusChangeListener { _, hasFocus ->
                    val background = GradientDrawable().apply {
                        setColor(if (hasFocus) Color.parseColor("#FF1E90FF") else Color.parseColor("#80000000"))
                        cornerRadius = 8f
                        setStroke(if (hasFocus) 4 else 2, Color.parseColor(if (hasFocus) "#FF1E90FF" else "#FFFFFF"))
                    }
                    leftButton.setBackgroundDrawable(background)
                    if (hasFocus) {
                        rightButton.setBackgroundDrawable(GradientDrawable().apply {
                            setColor(Color.parseColor("#80000000"))
                            cornerRadius = 8f
                            setStroke(2, Color.parseColor("#FFFFFF"))
                        })
                    }
                }

                buttonLayout.addView(leftButton)
                buttonLayout.addView(rightButton)

                // 使用版本描述
                val displayText = if (update && release?.description != null) {
                    "${activity.getString(R.string.latest_version, release?.version_name)}\n${release?.description}"
                } else {
                    text
                }

                val dialog = AlertDialog.Builder(activity)
                    .setMessage(displayText)
                    .setView(buttonLayout)
                    .setCancelable(true)
                    .create()

                dialog.setOnKeyListener { _, keyCode, event ->
                    if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                        dialog.dismiss()
                        onCancel()
                        true
                    } else {
                        false
                    }
                }

                dialog.show()

                // 设置按钮点击事件
                rightButton.setOnClickListener {
                    dialog.dismiss()
                    if (rightButtonAction != null) {
                        rightButtonAction()
                    } else if (update) {
                        release?.let { startDownload(it, isDirectUpdate = true) }
                    }
                }
                leftButton.setOnClickListener {
                    dialog.dismiss()
                    if (leftButtonAction != null) {
                        leftButtonAction()
                    } else {
                        onCancel()
                    }
                }

                // 初始设置焦点并高亮
                val focusButton = if (defaultFocusOnLeft) leftButton else rightButton
                focusButton.post {
                    focusButton.requestFocus()
                    focusButton.setBackgroundDrawable(GradientDrawable().apply {
                        setColor(Color.parseColor("#FF1E90FF"))
                        cornerRadius = 8f
                        setStroke(4, Color.parseColor("#FF1E90FF"))
                    })
                }

                // 10秒后自动关闭
                Handler(Looper.getMainLooper()).postDelayed({
                    if (dialog.isShowing) {
                        dialog.dismiss()
                        onCancel()
                    }
                }, 20000)

                // 调整对话框位置和大小
                val window = dialog.window
                window?.let {
                    val params = it.attributes
                    val density = activity.resources.displayMetrics.density
                    val widthInPixels = (330 * density).toInt()
                    val heightInPixels = (150 * density).toInt() // 增加高度以容纳描述
                    params.width = widthInPixels
                    params.height = heightInPixels
                    params.gravity = android.view.Gravity.TOP or android.view.Gravity.END
                    params.x = -50
                    params.y = 50
                    it.attributes = params
                    val background = GradientDrawable().apply {
                        setColor(Color.parseColor("#4D000000"))
                        cornerRadius = 16f
                        setStroke(2, Color.parseColor("#FF666666"))
                    }
                    it.setBackgroundDrawable(background)
                }

                // 设置消息文本样式并减少上下边距
                dialog.findViewById<TextView>(android.R.id.message)?.let { message ->
                    message.gravity = android.view.Gravity.CENTER
                    message.setTextColor(Color.parseColor("#FFFFFFFF"))
                    message.setPadding(0, 0, 0, 0)
                    message.textSize = 16f
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to show AlertDialog: ${e.message}")
                Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
                if (update) {
                    showInstallOptions()
                }
            }
        }
    }

    private fun startDownload(release: ReleaseResponse, isDirectUpdate: Boolean) {
        if (release.apk_name.isNullOrEmpty() || release.apk_url.isNullOrEmpty() || release.version_name.isNullOrEmpty()) {
            Log.e(TAG, "Invalid release data")
            activity.runOnUiThread {
                Toast.makeText(activity, R.string.download_failed, Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "Requesting WRITE_EXTERNAL_STORAGE permission")
                ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 100)
                activity.runOnUiThread {
                    Toast.makeText(activity, R.string.grant_storage_permissions, Toast.LENGTH_LONG).show()
                }
                return
            }
        }
        CoroutineScope(Dispatchers.Main).launch {
            var progressDialog: AlertDialog? = null // 声明为可空变量
            try {
                // 检查存储状态
                if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) {
                    Log.e(TAG, "External storage not mounted")
                    activity.runOnUiThread {
                        Toast.makeText(activity, R.string.external_storage_unavailable, Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                // 创建自定义 AlertDialog 显示进度条
                val progressLayout = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(50, 50, 50, 50)
                }
                val progressText = TextView(activity).apply {
                    text = activity.getString(R.string.is_downloading)
                    gravity = android.view.Gravity.CENTER
                    setPadding(0, 0, 0, 20)
                }
                val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    progress = 0
                }
                progressLayout.addView(progressText)
                progressLayout.addView(progressBar)

                progressDialog = AlertDialog.Builder(activity)
                    .setView(progressLayout)
                    .setCancelable(false)
                    .create()
                progressDialog?.show()

                // 调整进度对话框样式
                progressDialog?.window?.let {
                    val params = it.attributes
                    params.width = (activity.resources.displayMetrics.widthPixels / 3) + 50
                    params.gravity = android.view.Gravity.CENTER
                    it.attributes = params
                    it.setBackgroundDrawableResource(android.R.color.transparent)
                }

                val fileName = if (release.apk_name?.contains(release.version_name!!) == true) {
                    release.apk_name!!
                } else {
                    "yourtv_v${release.version_name}.apk"
                }
                val result = UpdateDownloader.downloadApk(
                    context = activity,
                    url = release.apk_url!!,
                    fileName = fileName,
                    onProgress = { progress ->
                        activity.runOnUiThread {
                            progressBar.progress = progress
                            if (progress == 100) {
                                progressDialog?.dismiss()
                            }
                        }
                    }
                )
                if (result.isSuccess) {
                    val apkFile = result.getOrNull()
                    activity.runOnUiThread {
                        val action = if (isDirectUpdate) {
                            activity.getString(R.string.update_direct)
                        } else {
                            activity.getString(R.string.update_manual)
                        }
                        val message = activity.getString(R.string.download_ready, action)
                        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                    }
                    apkFile?.let {
                        Log.i(TAG, "Downloaded APK path: ${it.absolutePath}")
                        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                                Log.i(TAG, "Requesting WRITE_EXTERNAL_STORAGE permission")
                                ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 100)
                                activity.runOnUiThread {
                                    Toast.makeText(activity, R.string.grant_storage_permissions, Toast.LENGTH_LONG).show()
                                }
                                return@launch
                            }
                        }
                        val receiver = DownloadReceiver(activity, fileName, this@UpdateManager)
                        if (isDirectUpdate) {
                            // 直接更新：使用私有目录的 APK 文件
                            Log.i(TAG, "Triggering direct install for $fileName")
                            sharedPrefs.edit().putString("pending_apk_path", it.absolutePath).apply()
                            if (activity is MainActivity) {
                                SP.time = true
                                activity.supportFragmentManager.beginTransaction()
                                    .hide(activity.supportFragmentManager.fragments
                                        .filterIsInstance<SettingFragment>()
                                        .firstOrNull() ?: return@let)
                                    .commitAllowingStateLoss()
                                activity.showTimeFragment()
                            }
                            receiver.installNewVersion(apkFile)
                            Handler(Looper.getMainLooper()).postDelayed({
                                Log.i(TAG, "Finishing activity after direct install")
                                activity.finishAffinity()
                            }, 1000)
                        } else {
                            // 卸载后安装：尝试移动到公共目录
                            Log.i(TAG, "Moving APK to public Downloads: $fileName")
                            val publicFile = moveToPublicDownloads(it, fileName)
                            if (publicFile != null) {
                                Log.i(TAG, "Saving pending_apk_path: ${publicFile.absolutePath}")
                                sharedPrefs.edit().putString("pending_apk_path", publicFile.absolutePath).apply()
                                Log.i(TAG, "Triggering uninstallAndInstall for $fileName")
                                receiver.uninstallAndInstall(publicFile)
                            } else {
                                Log.e(TAG, "Failed to move APK to public Downloads")
                                activity.runOnUiThread {
                                    Toast.makeText(activity, "無法保存到 Downloads 目錄，請檢查存儲權限或空間", Toast.LENGTH_LONG).show()
                                }
                                // 回退到私有目录
                                if (apkFile.exists() && apkFile.canRead()) {
                                    Log.i(TAG, "Falling back to private directory: ${apkFile.absolutePath}")
                                    sharedPrefs.edit().putString("pending_apk_path", apkFile.absolutePath).apply()
                                    Log.i(TAG, "Triggering uninstallAndInstall with private file: $fileName")
                                    receiver.uninstallAndInstall(apkFile)
                                } else {
                                    Log.e(TAG, "Private file inaccessible: ${apkFile.absolutePath}")
                                    activity.runOnUiThread {
                                        Toast.makeText(activity, R.string.download_failed, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    } ?: run {
                        Log.e(TAG, "Downloaded file is null")
                        activity.runOnUiThread {
                            Toast.makeText(activity, R.string.download_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    Log.e(TAG, "Download failed: ${result.exceptionOrNull()?.message}")
                    activity.runOnUiThread {
                        progressDialog?.dismiss() // 安全调用
                        Toast.makeText(activity, R.string.download_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download error: ${e.message}", e)
                activity.runOnUiThread {
                    progressDialog?.dismiss() // 安全调用
                    Toast.makeText(activity, R.string.download_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun moveToPublicDownloads(apkFile: File, fileName: String): File? {
        try {
            Log.i(TAG, "Attempting to move APK from ${apkFile.absolutePath} to Downloads directory")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 使用 MediaStore
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                // 检查现有文件
                val existingUri = activity.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Downloads._ID),
                    "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?",
                    arrayOf(fileName, Environment.DIRECTORY_DOWNLOADS),
                    null
                )
                existingUri?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                        val deleteUri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                        Log.i(TAG, "Deleting existing file via MediaStore: $fileName")
                        activity.contentResolver.delete(deleteUri, null, null)
                    }
                }
                val uri = activity.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                uri?.let {
                    activity.contentResolver.openOutputStream(uri)?.use { output ->
                        apkFile.inputStream().use { input ->
                            input.copyTo(output)
                        }
                    }
                    Log.i(TAG, "Moved APK to Downloads via MediaStore: $fileName")
                    if (apkFile.delete()) {
                        Log.i(TAG, "Deleted original APK: ${apkFile.absolutePath}")
                    } else {
                        Log.w(TAG, "Failed to delete original APK: ${apkFile.absolutePath}")
                    }
                    return File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
                }
                Log.e(TAG, "Failed to insert into MediaStore")
                return null
            } else {
                // Android 9- 使用传统文件操作
                val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                Log.i(TAG, "Public Downloads directory: ${publicDir.absolutePath}")
                if (!publicDir.exists() && !publicDir.mkdirs()) {
                    Log.e(TAG, "Failed to create Downloads directory")
                    return null
                }
                val publicFile = File(publicDir, fileName)
                if (publicFile.exists()) {
                    Log.i(TAG, "Existing file found, deleting: ${publicFile.absolutePath}")
                    if (!publicFile.delete()) {
                        Log.e(TAG, "Failed to delete existing file: ${publicFile.absolutePath}")
                        // 尝试重命名目标文件
                        val tempFileName = "temp_${System.currentTimeMillis()}_$fileName"
                        val tempFile = File(publicDir, tempFileName)
                        Log.i(TAG, "Renaming to temporary file: ${tempFile.absolutePath}")
                        if (publicFile.renameTo(tempFile)) {
                            Log.i(TAG, "Renamed existing file to ${tempFile.absolutePath}")
                            if (!tempFile.delete()) {
                                Log.w(TAG, "Failed to delete temporary file: ${tempFile.absolutePath}")
                            }
                        } else {
                            Log.e(TAG, "Failed to rename existing file")
                        }
                    }
                }
                Log.i(TAG, "Copying APK to ${publicFile.absolutePath}")
                apkFile.copyTo(publicFile, overwrite = true)
                Log.i(TAG, "Deleting original APK: ${apkFile.absolutePath}")
                if (!apkFile.delete()) {
                    Log.w(TAG, "Failed to delete original APK: ${apkFile.absolutePath}")
                }
                Log.i(TAG, "Moved APK successfully to ${publicFile.absolutePath}")
                return publicFile
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to move APK to Downloads: ${e.message}", e)
            return null
        }
    }

    override fun onConfirm() {
        Log.i(TAG, "onConfirm $release")
        release?.let { startDownload(it, isDirectUpdate = true) }
    }

    override fun onCancel() {
        Log.i(TAG, "onCancel called")
        activity.runOnUiThread {
            Toast.makeText(activity, "已取消下載", Toast.LENGTH_SHORT).show()
        }
        // 清除 pending APK 路径
        sharedPrefs.edit().remove("pending_apk_path").apply()
    }

    fun destroy() {
        Log.i(TAG, "Destroyed UpdateManager")
        // 清除 pending APK 路径
        sharedPrefs.edit().remove("pending_apk_path").apply()
    }

    companion object {
        private const val TAG = "UpdateManager"
        private const val VERSION_URL =
            "https://raw.githubusercontent.com/peterwangze/yourtv/main/version.json"
    }
}

class DownloadReceiver(
    private val context: Context,
    private val apkFileName: String,
    private val updateManager: UpdateManager
) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.w(TAG, "Broadcast received but not used with OkHttp")
    }

    fun installNewVersion(apkFile: File) {
        Log.i(TAG, "Starting installNewVersion: ${apkFile.absolutePath}")
        if (!apkFile.exists() || !apkFile.canRead()) {
            Log.e(TAG, "APK file inaccessible: exists=${apkFile.exists()}, readable=${apkFile.canRead()}, path=${apkFile.absolutePath}")
            showToast(R.string.install_failed)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            Log.w(TAG, "Unknown sources installation disabled")
            try {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                Log.i(TAG, "Starting unknown sources settings intent")
                context.startActivity(intent)
                showToast(R.string.enable_unknown_sources)
                return
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open unknown sources settings: ${e.message}", e)
                showToast(R.string.install_failed)
                return
            }
        }

        try {
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            Log.i(TAG, "Generated apkUri: $apkUri")
            // 验证 URI 可访问
            context.contentResolver.openInputStream(apkUri)?.use { it.close() } ?: run {
                Log.e(TAG, "URI is not accessible: $apkUri")
                showToast(R.string.install_failed)
                return
            }

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            Log.i(TAG, "Checking if ACTION_VIEW intent can be resolved")
            val resolvedActivity = installIntent.resolveActivity(context.packageManager)
            if (resolvedActivity == null) {
                Log.e(TAG, "No activity found to handle ACTION_VIEW intent")
                showToast(R.string.install_failed)
                return
            }
            Log.i(TAG, "Resolved activity for install: $resolvedActivity")
            context.startActivity(installIntent)
            Log.i(TAG, "Install intent started successfully")
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "FileProvider error: ${e.message}", e)
            showToast("無法訪問 APK 文件，請檢查存儲權限")
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception during install: ${e.message}", e)
            showToast(R.string.install_failed)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install APK: ${e.message}", e)
            showToast(R.string.install_failed)
            if (e.message?.contains("signature") == true || e.message?.contains("package conflict") == true) {
                showToast("簽名衝突，請選擇卸載後安裝")
                updateManager.checkAndUpdate()
            }
        }
    }

    fun uninstallAndInstall(apkFile: File) {
        Log.i(TAG, "Starting uninstallAndInstall: ${apkFile.absolutePath}")
        if (!apkFile.exists() || !apkFile.canRead()) {
            Log.e(TAG, "APK file inaccessible: exists=${apkFile.exists()}, readable=${apkFile.canRead()}")
            showToast(R.string.install_failed)
            return
        }
        try {
            Log.i(TAG, "Context type: ${context.javaClass.simpleName}, package: ${context.packageName}")
            // 首先尝试 ACTION_DELETE
            var uninstallIntent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            Log.i(TAG, "Checking if ACTION_DELETE intent can be resolved")
            var resolvedActivity = uninstallIntent.resolveActivity(context.packageManager)
            if (resolvedActivity == null) {
                Log.w(TAG, "ACTION_DELETE not supported, trying ACTION_UNINSTALL_PACKAGE")
                // 回退到 ACTION_UNINSTALL_PACKAGE
                uninstallIntent = Intent(Intent.ACTION_UNINSTALL_PACKAGE).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                resolvedActivity = uninstallIntent.resolveActivity(context.packageManager)
                if (resolvedActivity == null) {
                    Log.e(TAG, "No activity found to handle ACTION_UNINSTALL_PACKAGE intent")
                    // 打开设置
                    try {
                        val settingsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:${context.packageName}")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(settingsIntent)
                        Log.i(TAG, "Opened application settings for manual uninstall")
                        showToast("請在設置中手動卸載應用後安裝")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to open settings: ${e.message}", e)
                        showToast("無法啟動卸載，請手動卸載後安裝")
                    }
                    installNewVersion(apkFile)
                    return
                }
            }
            Log.i(TAG, "Resolved activity: $resolvedActivity")
            Log.i(TAG, "Starting uninstall intent: ${uninstallIntent.action}")
            context.startActivity(uninstallIntent)
            Log.i(TAG, "Uninstall intent started successfully")
            showToast("請卸載應用後，在 ${apkFile.parent} 目錄中打開 $apkFileName 安裝新版本")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start uninstall: ${e.message}", e)
            showToast("卸載失敗，請手動卸載後安裝")
            installNewVersion(apkFile)
        }
    }

    private fun showToast(stringResId: Int) {
        (context as? FragmentActivity)?.runOnUiThread {
            Toast.makeText(context, stringResId, Toast.LENGTH_SHORT).show()
        } ?: Toast.makeText(context, stringResId, Toast.LENGTH_SHORT).show()
    }

    private fun showToast(message: String) {
        Log.i(TAG, "Showing toast: $message")
        (context as? FragmentActivity)?.runOnUiThread {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        } ?: run {
            Log.w(TAG, "Context is not FragmentActivity, using default context")
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val TAG = "UpdateManager"
    }
}

object UpdateDownloader {
    private const val TAG = "UpdateManager"

    suspend fun downloadApk(
        context: Context,
        url: String,
        fileName: String,
        onProgress: (Int) -> Unit
    ): Result<File> {
        val downloadDir = try {
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to access download directory: ${e.message}")
            return Result.failure(IOException("Failed to access download directory"))
        }
        downloadDir.mkdirs()
        val targetFile = File(downloadDir, fileName)

        // 删除同名文件
        if (targetFile.exists()) {
            targetFile.delete()
            Log.i(TAG, "Deleted existing file: ${targetFile.absolutePath}")
        }

        // 获取成功代理前缀
        val sharedPrefs = context.getSharedPreferences("UpdatePrefs", Context.MODE_PRIVATE)
        val proxyPrefix = sharedPrefs.getString("successful_proxy_prefix", null)
        val urls = Utils.getUrls(url).toMutableList().apply { add(url) }
        if (proxyPrefix != null && proxyPrefix != "proxy") {
            val proxyUrl = "$proxyPrefix/$url"
            urls.add(0, proxyUrl)
            Log.i(TAG, "Prioritizing cached proxy URL for APK download: $proxyUrl")
        } else {
            Log.i(TAG, "No valid cached proxy prefix, using default URLs: $urls")
        }

        // 顺序尝试下载
        for (requestUrl in urls) {
            Log.d(TAG, "Downloading from: $requestUrl")
            try {
                val request = Request.Builder()
                    .url(requestUrl)
                    .addHeader("User-Agent", "okhttp/3.15")
                    .build()
                val response = withContext(Dispatchers.IO) {
                    HttpClient.okHttpClient.newCall(request).execute()
                }
                if (response.isSuccessful) {
                    response.body?.let { body ->
                        val totalBytes = body.contentLength()
                        var downloadedBytes = 0L
                        var lastProgress = -1
                        targetFile.outputStream().use { output ->
                            body.byteStream().use { input ->
                                val buffer = ByteArray(8192)
                                var bytesRead: Int
                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    output.write(buffer, 0, bytesRead)
                                    downloadedBytes += bytesRead
                                    if (totalBytes > 0) {
                                        val progress = (downloadedBytes * 100 / totalBytes).toInt()
                                        if (progress != lastProgress && progress % 10 == 0) {
                                            withContext(Dispatchers.Main) {
                                                onProgress(progress)
                                            }
                                            lastProgress = progress
                                        }
                                    }
                                }
                            }
                        }
                        Log.i(TAG, "Downloaded APK to ${targetFile.absolutePath}, size=${targetFile.length()}")
                        if (targetFile.length() == 0L) {
                            return Result.failure(IOException("Downloaded file is empty"))
                        }
                        return Result.success(targetFile)
                    } ?: return Result.failure(IOException("Response body is null"))
                } else {
                    Log.e(TAG, "Download failed with code ${response.code}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download file failed for $requestUrl: ${e.message}")
            }
        }

        // 所有 URL 失败，尝试代理下载
        Log.e(TAG, "All download attempts failed, attempting proxy download")
        val result = DownGithubPrivate.download(context, url)
        if (result.isSuccess) {
            val content = result.getOrThrow()
            targetFile.writeText(content)
            Log.i(TAG, "Downloaded APK via proxy to ${targetFile.absolutePath}, size=${targetFile.length()}")
            if (targetFile.length() == 0L) {
                return Result.failure(IOException("Downloaded file is empty"))
            }
            return Result.success(targetFile)
        }

        Log.e(TAG, "Proxy download failed")
        return Result.failure(IOException("All download attempts failed"))
    }
}
