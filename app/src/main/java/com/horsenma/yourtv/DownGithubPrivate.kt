package com.horsenma.yourtv

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.gson.JsonObject
import com.horsenma.yourtv.data.Global.gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Authenticator
import java.net.HttpURLConnection
import java.net.PasswordAuthentication
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.random.Random

object DownGithubPrivate {
    private const val TAG = "DownGithubPrivate"
    private const val WORKER_API_URL = "https://yourtv.horsenma.top"
    private var cachedPrivateRepos: Pair<Map<String, String>, ProxyInfo?>? = null

    private fun getCachedPrivateRepos(context: Context): Pair<Map<String, String>, ProxyInfo?> {
        return cachedPrivateRepos ?: run {
            val result = loadPrivateRepos(context)
            cachedPrivateRepos = result
            result
        }
    }

    data class ProxyInfo(
        val host: String,
        val port: Int,
        val username: String,
        val password: String
    )

    suspend fun download(context: Context, url: String, id: String = ""): Result<String> {
        // 总超时兜底：DNS/镜像逐个尝试可能远超单次 readTimeout，45 秒后强制放弃
        return withTimeoutOrNull(45_000L) {
            withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Download called with url=$url, id=$id, thread=${Thread.currentThread().name}")
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    Log.d(TAG, "Attempting Worker API download for fileName=$url")
                    val apiKeys = UserInfoManager.apiKeys ?: run {
                        Log.e(TAG, "API keys not loaded")
                        return@withContext Result.failure(Exception("API keys not loaded"))
                    }
                    if (apiKeys.worker_api_key.isNullOrEmpty()) {
                        Log.e(TAG, "Worker API key not set")
                        return@withContext Result.failure(Exception("Worker API key not set"))
                    }
                    val startTime = System.currentTimeMillis()
                    val content = downloadWorkerFile(apiKeys.worker_api_key, url)
                    val duration = System.currentTimeMillis() - startTime
                    if (content != null) {
                        Log.d(TAG, "Worker download successful: $url (content length: ${content.length}, took ${duration}ms)")
                        return@withContext Result.success(content)
                    }
                    Log.w(TAG, "Worker download failed or returned null after ${duration}ms, attempting GitHub fallback")
                    // 恢复私有仓库逻辑
                    val (repo, branch, filePath) = parseGitHubUrl("https://github.com/horsenmail/yourtv/raw/main/$url")
                    val (privateRepos, proxyInfo) = getCachedPrivateRepos(context)
                    val isPrivateRepo = repo != null && privateRepos.containsKey(repo)
                    val token = if (isPrivateRepo) privateRepos[repo] else null
                    if (isPrivateRepo && token != null && filePath != null && branch != null) {
                        val apiUrl = "https://api.github.com/repos/$repo/contents/$filePath?ref=$branch"
                        Log.d(TAG, "Fetching download URL from: $apiUrl")
                        val downloadUrl = fetchDownloadUrl(context, apiUrl, token, proxyInfo)
                        if (downloadUrl != null) {
                            return@withContext downloadFile(context, listOf(downloadUrl), useProxy = proxyInfo != null)
                        } else {
                            return@withContext Result.failure(IOException("Failed to fetch download URL"))
                        }
                    } else {
                        val githubUrl = "https://raw.githubusercontent.com/horsenmail/yourtv/main/$url"
                        Log.d(TAG, "Falling back to GitHub URL: $githubUrl")
                        // 国内网络优先走 GitHub 加速镜像，原地址最后兜底
                        val candidates = (Utils.getUrls(githubUrl) + listOf(githubUrl)).distinct()
                        return@withContext downloadFile(context, candidates, useProxy = proxyInfo != null)
                    }
                }
                val (repo, branch, filePath) = parseGitHubUrl(url)
                val (privateRepos, proxyInfo) = getCachedPrivateRepos(context)
                val isPrivateRepo = repo != null && privateRepos.containsKey(repo)
                val token = if (isPrivateRepo) privateRepos[repo] else null
                if (isPrivateRepo && token != null && filePath != null && branch != null) {
                    val apiUrl = "https://api.github.com/repos/$repo/contents/$filePath?ref=$branch"
                    Log.d(TAG, "Fetching download URL from: $apiUrl")
                    val downloadUrl = fetchDownloadUrl(context, apiUrl, token, proxyInfo)
                    if (downloadUrl != null) {
                        return@withContext downloadFile(context, listOf(downloadUrl), useProxy = proxyInfo != null)
                    } else {
                        return@withContext Result.failure(IOException("Failed to fetch download URL"))
                    }
                    } else {
                        // 国内网络优先走 GitHub 加速镜像，原地址最后兜底
                        val candidates = (Utils.getUrls(url) + listOf(url)).distinct()
                        return@withContext downloadFile(context, candidates, useProxy = proxyInfo != null)
                    }
                } catch (e: Exception) {
                Log.e(TAG, "Download failed for $url: ${e.javaClass.simpleName} - ${e.message}", e)
                return@withContext Result.failure(e)
            }
        }
        } ?: run {
            Log.w(TAG, "download: overall 45s timeout for $url");
            Result.failure(IOException("All download attempts timed out"))
        }
    }

    private suspend fun downloadWorkerFile(apiKey: String, fileName: String): String? {
        // return null // 立即返回 null，跳过 Worker API
        val startTime = System.currentTimeMillis()
        return try {
            Log.d(TAG, "Starting Worker API download: fileName=$fileName")
            withTimeout(20000) {
                val encodedFileName = java.net.URLEncoder.encode(fileName, "UTF-8")
                val url = URL("$WORKER_API_URL/m3u/$encodedFileName")
                val connection = url.openConnection() as HttpsURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("X-API-Key", apiKey)
                connection.setRequestProperty("Accept", "application/octet-stream, application/json")
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                try {
                    when (connection.responseCode) {
                        HttpURLConnection.HTTP_OK -> {
                            val contentLength = connection.contentLength
                            Log.d(TAG, "Received response with Content-Length: $contentLength bytes (t=${System.currentTimeMillis() - startTime}ms)")
                            if (contentLength == 0) {
                                Log.w(TAG, "File $fileName is empty")
                                return@withTimeout null
                            }
                            connection.inputStream.bufferedReader().use { reader ->
                                val content = reader.readText()
                                if (content.isEmpty()) {
                                    Log.e(TAG, "Worker content empty for $fileName (t=${System.currentTimeMillis() - startTime}ms)")
                                    return@withTimeout null
                                }
                                content
                            }
                        }
                        HttpURLConnection.HTTP_NOT_FOUND -> {
                            Log.w(TAG, "File $fileName not found (404)")
                            null
                        }
                        else -> {
                            val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                            Log.e(TAG, "Worker API returned ${connection.responseCode} for $fileName: $errorBody")
                            null
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.e(TAG, "Worker download timed out for $fileName after ${System.currentTimeMillis() - startTime}ms", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Worker download failed for $fileName: ${e.javaClass.simpleName} - ${e.message}", e)
            null
        }
    }

    private suspend fun downloadFile(
        context: Context,
        urls: List<String>,
        useProxy: Boolean,
        maxRetries: Int = 2,
        totalTimeoutMs: Long = 45_000
    ): Result<String> {
        Log.d(TAG, "Attempting download for URLs: $urls")
        val startTime = System.currentTimeMillis()
        for (targetUrl in urls) {
            if (System.currentTimeMillis() - startTime > totalTimeoutMs) {
                Log.w(TAG, "Download timed out after ${totalTimeoutMs}ms, giving up")
                break
            }
            Log.d(TAG, "Trying URL: $targetUrl")
            repeat(maxRetries) { retry ->
                try {
                    val content = withContext(Dispatchers.IO) {
                        val connection = URL(targetUrl).openConnection() as HttpURLConnection
                        connection.requestMethod = "GET"
                        connection.setRequestProperty("User-Agent", "okhttp/3.15")
                        connection.setRequestProperty("Accept", "text/plain")
                        connection.connectTimeout = 8000
                        connection.readTimeout = 8000
                        if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                            val rawContent = connection.inputStream.bufferedReader().use { it.readText() }
                            // 判断是否为 HEX 文件
                            val isHex = rawContent.trim().matches(Regex("^[0-9a-fA-F]+$"))
                            if (isHex) {
                                // HEX 文件：移除换行符和空白
                                rawContent.trim().replace(Regex("[\\r\\n\\s]+"), "")
                            } else {
                                // 明文文件：保留原始内容
                                rawContent
                            }
                        } else {
                            throw IOException("HTTP ${connection.responseCode}")
                        }
                    }
                    if (content.isEmpty()) {
                        Log.e(TAG, "Downloaded content empty for $targetUrl")
                        return Result.failure(IOException("Downloaded content is empty"))
                    }
                    Log.d(TAG, "Downloaded successfully for $targetUrl (content length: ${content.length})")
                    return Result.success(content)
                } catch (e: Exception) {
                    Log.e(TAG, "Download failed for $targetUrl, retry $retry/$maxRetries: ${e.message}", e)
                    if (retry == maxRetries - 1) {
                        Log.w(TAG, "Download failed for $targetUrl after $maxRetries retries, trying next URL")
                    }
                }
            }
        }
        if (useProxy) {
            Log.d(TAG, "All URLs failed, trying SOCKS proxy from github_private.txt")
            val (_, proxyInfo) = getCachedPrivateRepos(context)
            if (proxyInfo != null) {
                return trySocksProxyDownload(context, urls.first(), proxyInfo, maxRetries)
            }
        }
        return Result.failure(IOException("All download attempts failed"))
    }

    private suspend fun trySocksProxyDownload(
        context: Context,
        url: String,
        proxyInfo: ProxyInfo,
        maxRetries: Int
    ): Result<String> {
        return withContext(Dispatchers.IO) {
            var retries = 0
            while (retries < maxRetries) {
                try {
                    Authenticator.setDefault(object : Authenticator() {
                        override fun getPasswordAuthentication(): PasswordAuthentication? {
                            if (requestingHost.equals(proxyInfo.host, ignoreCase = true) && requestingPort == proxyInfo.port) {
                                return PasswordAuthentication(proxyInfo.username, proxyInfo.password.toCharArray())
                            }
                            return null
                        }
                    })
                    val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(proxyInfo.host, proxyInfo.port))
                    val connection = URL(url).openConnection(proxy) as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.setRequestProperty("User-Agent", "okhttp/3.15")
                    connection.setRequestProperty("Accept", "text/plain")
                    connection.connectTimeout = 10000
                    connection.readTimeout = 10000
                    val content = connection.inputStream.bufferedReader().use { it.readText() }.let { rawContent ->
                        // 判断是否为 HEX 文件
                        val isHex = rawContent.trim().matches(Regex("^[0-9a-fA-F]+$"))
                        if (isHex) {
                            // HEX 文件：移除换行符和空白
                            rawContent.trim().replace(Regex("[\\r\\n\\s]+"), "")
                        } else {
                            // 明文文件：保留原始内容
                            rawContent
                        }
                    }
                    if (content.isEmpty()) {
                        return@withContext Result.failure(IOException("Downloaded content is empty"))
                    }
                    Log.d(TAG, "Downloaded successfully via SOCKS proxy for $url (content length: ${content.length})")
                    return@withContext Result.success(content)
                } catch (e: Exception) {
                    Log.e(TAG, "SOCKS proxy download failed for $url, retry $retries/$maxRetries: ${e.message}", e)
                    retries++
                    if (retries < maxRetries) delay(Random.nextLong(500, 1500))
                } finally {
                    Authenticator.setDefault(null)
                }
            }
            Result.failure(IOException("SOCKS proxy download failed after $maxRetries retries"))
        }
    }

    private suspend fun fetchDownloadUrl(context: Context, apiUrl: String, token: String, proxyInfo: ProxyInfo?, maxRetries: Int = 3): String? {
        return withContext(Dispatchers.IO) {
            var retries = 0
            while (retries < maxRetries) {
                try {
                    val connection = if (proxyInfo != null && retries > 0) {
                        Authenticator.setDefault(object : Authenticator() {
                            override fun getPasswordAuthentication(): PasswordAuthentication? {
                                if (requestingHost.equals(proxyInfo.host, ignoreCase = true) && requestingPort == proxyInfo.port) {
                                    return PasswordAuthentication(proxyInfo.username, proxyInfo.password.toCharArray())
                                }
                                return null
                            }
                        })
                        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(proxyInfo.host, proxyInfo.port))
                        URL(apiUrl).openConnection(proxy) as HttpsURLConnection
                    } else {
                        URL(apiUrl).openConnection() as HttpsURLConnection
                    }
                    connection.requestMethod = "GET"
                    connection.setRequestProperty("Authorization", "token $token")
                    connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                    connection.setRequestProperty("User-Agent", "okhttp/3.15")
                    connection.connectTimeout = 10000
                    connection.readTimeout = 10000
                    try {
                        if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                            val jsonStr = connection.inputStream.bufferedReader().use { it.readText() }
                            val json = gson.fromJson(jsonStr, JsonObject::class.java)
                            val downloadUrl = json.get("download_url")?.asString
                            Log.d(TAG, "Fetched download URL: $downloadUrl")
                            return@withContext downloadUrl
                        } else {
                            Log.w(TAG, "API request failed with code ${connection.responseCode}, retry $retries/$maxRetries")
                            retries++
                            delay(Random.nextLong(500, 1500))
                        }
                    } finally {
                        if (proxyInfo != null && retries > 0) {
                            Authenticator.setDefault(null)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Fetch download URL failed, retry $retries/$maxRetries: ${e.message}", e)
                    retries++
                    if (retries < maxRetries) delay(Random.nextLong(500, 1500))
                }
            }
            Log.e(TAG, "Failed to fetch download URL after $maxRetries retries")
            null
        }
    }

    private fun loadPrivateRepos(context: Context): Pair<Map<String, String>, ProxyInfo?> {
        try {
            val jsonStr = context.resources.openRawResource(R.raw.github_private)
                .bufferedReader()
                .use { it.readText() }
            if (jsonStr.isNotEmpty()) {
                val decryptedJson = SourceDecoder.decodeHexSource(jsonStr)
                if (decryptedJson != null && decryptedJson.trim().startsWith("{") && decryptedJson.trim().endsWith("}")) {
                    val json = gson.fromJson(decryptedJson, JsonObject::class.java)
                    val repos = json.entrySet()
                        .filter { it.key != "proxy" }
                        .associate { it.key to it.value.asString }
                    val proxyInfo = json.getAsJsonObject("proxy")?.let {
                        ProxyInfo(
                            host = it.get("host")?.asString ?: return@let null,
                            port = it.get("port")?.asInt ?: return@let null,
                            username = it.get("username")?.asString ?: return@let null,
                            password = it.get("password")?.asString ?: return@let null
                        )
                    }
                    return Pair(repos, proxyInfo)
                } else {
                    Log.w(TAG, "Decrypted JSON is not valid: ${decryptedJson?.take(100) ?: "null"}")
                }
            } else {
                Log.w(TAG, "github_private resource is empty")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load private repos: ${e.message}", e)
        }
        return Pair(emptyMap(), null)
    }

    private fun parseGitHubUrl(url: String): Triple<String?, String?, String?> {
        val rawUrl = url.replace(Regex("https://[^/]+/(https?://(?:raw\\.githubusercontent\\.com|github\\.com)/.*)"), "$1")
        val regex = Regex(
            "^https?://(?:raw\\.githubusercontent\\.com|github\\.com)/([^/]+)/([^/]+)/(?:raw/)?([^/]+)/(.+)$"
        )
        return regex.find(rawUrl)?.let {
            val (user, repo, branch, filePath) = it.destructured
            Triple("$user/$repo", branch, filePath)
        } ?: Triple(null, null, null)
    }

    suspend fun uploadFile(
        context: Context,
        repo: String,
        filePath: String,
        branch: String,
        updatedContent: String,
        commitMessage: String,
        maxRetries: Int = 3
    ): Result<Unit> {
        var retries = 0
        val apiUrl = "https://api.github.com/repos/$repo/contents/$filePath?ref=$branch"
        val (privateRepos, proxyInfo) = getCachedPrivateRepos(context)

        while (retries <= maxRetries) {
            try {
                val token = privateRepos[repo] ?: return Result.failure(IOException("No token found for repo $repo"))
                var useProxy = retries > 0 && proxyInfo != null

                var sha: String? = null
                var currentContent = ""
                if (useProxy && proxyInfo != null) {
                    Log.d(TAG, "Fetching SHA for $filePath with SOCKS proxy")
                    val result = trySocksProxyDownload(context, apiUrl, proxyInfo, 1)
                    if (result.isSuccess) {
                        val jsonStr = result.getOrNull() ?: return Result.failure(IOException("Empty response"))
                        val json = gson.fromJson(jsonStr, JsonObject::class.java)
                        sha = json.get("sha")?.asString
                        currentContent = json.get("content")?.asString?.let { String(Base64.decode(it, Base64.DEFAULT)) } ?: ""
                    } else {
                        throw IOException("SOCKS proxy fetch failed: ${result.exceptionOrNull()?.message}")
                    }
                } else {
                    Log.d(TAG, "Fetching SHA for $filePath with direct")
                    val (responseCode, responseBody) = withContext(Dispatchers.IO) {
                        val getConnection = URL(apiUrl).openConnection() as HttpsURLConnection
                        getConnection.requestMethod = "GET"
                        getConnection.setRequestProperty("Authorization", "token $token")
                        getConnection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                        getConnection.setRequestProperty("User-Agent", "okhttp/3.15")
                        getConnection.connectTimeout = 10000
                        getConnection.readTimeout = 10000
                        val code = getConnection.responseCode
                        val body = if (code == HttpURLConnection.HTTP_OK) {
                            getConnection.inputStream.bufferedReader().use { it.readText() }
                        } else {
                            ""
                        }
                        code to body
                    }
                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        val json = gson.fromJson(responseBody, JsonObject::class.java)
                        sha = json.get("sha")?.asString
                        currentContent = json.get("content")?.asString?.let { String(Base64.decode(it, Base64.DEFAULT)) } ?: ""
                    } else if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                        Log.d(TAG, "File $filePath does not exist, will create new")
                    } else {
                        Log.e(TAG, "Failed to fetch file SHA: $responseCode")
                        retries++
                        delay(Random.nextLong(500, 1500))
                        continue
                    }
                }

                val decodedCurrent = SourceDecoder.decodeHexSource(currentContent)
                if (currentContent.isNotEmpty() && decodedCurrent == null) {
                    Log.e(TAG, "Failed to decode current content")
                    retries++
                    delay(Random.nextLong(500, 1500))
                    continue
                }

                val encodedContent = Base64.encodeToString(updatedContent.toByteArray(), Base64.NO_WRAP)
                val requestBodyJson = JsonObject().apply {
                    addProperty("message", commitMessage)
                    addProperty("content", encodedContent)
                    if (sha != null) addProperty("sha", sha)
                    addProperty("branch", branch)
                }
                val requestBody = requestBodyJson.toString().toByteArray()

                if (useProxy && proxyInfo != null) {
                    Log.d(TAG, "Uploading $filePath with SOCKS proxy")
                    val putResponseCode = withContext(Dispatchers.IO) {
                        Authenticator.setDefault(object : Authenticator() {
                            override fun getPasswordAuthentication(): PasswordAuthentication? {
                                if (requestingHost.equals(proxyInfo.host, ignoreCase = true) && requestingPort == proxyInfo.port) {
                                    return PasswordAuthentication(proxyInfo.username, proxyInfo.password.toCharArray())
                                }
                                return null
                            }
                        })
                        val putConnection = Proxy(Proxy.Type.SOCKS, InetSocketAddress(proxyInfo.host, proxyInfo.port))
                            .let { URL(apiUrl).openConnection(it) as HttpsURLConnection }
                        putConnection.requestMethod = "PUT"
                        putConnection.setRequestProperty("Authorization", "token $token")
                        putConnection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                        putConnection.setRequestProperty("Content-Type", "application/json")
                        putConnection.setRequestProperty("User-Agent", "okhttp/3.15")
                        putConnection.connectTimeout = 10000
                        putConnection.readTimeout = 10000
                        putConnection.doOutput = true
                        putConnection.outputStream.use { it.write(requestBody) }
                        try {
                            putConnection.responseCode
                        } finally {
                            Authenticator.setDefault(null)
                        }
                    }
                    if (putResponseCode == HttpURLConnection.HTTP_OK || putResponseCode == HttpURLConnection.HTTP_CREATED) {
                        Log.d(TAG, "Successfully uploaded $filePath to $repo")
                        return Result.success(Unit)
                    } else if (putResponseCode == HttpURLConnection.HTTP_CONFLICT) {
                        Log.w(TAG, "Conflict detected, retrying ($retries/$maxRetries)")
                        retries++
                        delay(Random.nextLong(500, 1500))
                        continue
                    } else {
                        throw IOException("Upload failed with code $putResponseCode")
                    }
                } else {
                    Log.d(TAG, "Uploading $filePath with direct")
                    val putResponseCode = withContext(Dispatchers.IO) {
                        val putConnection = URL(apiUrl).openConnection() as HttpsURLConnection
                        putConnection.requestMethod = "PUT"
                        putConnection.setRequestProperty("Authorization", "token $token")
                        putConnection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                        putConnection.setRequestProperty("Content-Type", "application/json")
                        putConnection.setRequestProperty("User-Agent", "okhttp/3.15")
                        putConnection.connectTimeout = 10000
                        putConnection.readTimeout = 10000
                        putConnection.doOutput = true
                        putConnection.outputStream.use { it.write(requestBody) }
                        putConnection.responseCode
                    }
                    if (putResponseCode == HttpURLConnection.HTTP_OK || putResponseCode == HttpURLConnection.HTTP_CREATED) {
                        Log.d(TAG, "Successfully uploaded $filePath to $repo")
                        return Result.success(Unit)
                    } else if (putResponseCode == HttpURLConnection.HTTP_CONFLICT) {
                        Log.w(TAG, "Conflict detected, retrying ($retries/$maxRetries)")
                        retries++
                        delay(Random.nextLong(500, 1500))
                        continue
                    } else {
                        Log.e(TAG, "Failed to upload file: $putResponseCode")
                        retries++
                        delay(Random.nextLong(500, 1500))
                        continue
                    }
                }
            } catch (e: Exception) {
                when (e) {
                    is android.os.NetworkOnMainThreadException -> {
                        Log.e(TAG, "Network operation on main thread for $apiUrl, retry $retries/$maxRetries", e)
                        return Result.failure(IllegalStateException("Network operation must not run on main thread", e))
                    }
                    else -> {
                        Log.e(TAG, "Upload attempt $retries failed for $apiUrl: ${e.message}", e)
                        retries++
                        delay(Random.nextLong(500, 1500))
                        if (retries > maxRetries) {
                            return Result.failure(IOException("Failed to update file after $maxRetries retries"))
                        }
                    }
                }
            }
        }
        Log.e(TAG, "All upload attempts failed after $maxRetries retries")
        return Result.failure(IOException("Failed to update file after $maxRetries retries"))
    }
}
