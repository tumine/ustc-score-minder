package com.ustc.scoreminder.data.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 更新信息数据类
 */
data class UpdateInfo(
    val latestVersion: String,
    val currentVersion: String,
    val hasUpdate: Boolean,
    val releaseUrl: String,
    val releaseNotes: String
)

/**
 * 通过 GitHub Releases API 检查应用更新
 */
@Singleton
class UpdateChecker @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "UpdateChecker"
        private const val GITHUB_API_URL =
            "https://api.github.com/repos/tumine/ustc-score-minder/releases/latest"
    }

    /**
     * 检查是否有新版本
     * @param currentVersion 当前版本号，例如 "1.1.2"
     * @return UpdateInfo 包含更新信息
     */
    suspend fun checkForUpdate(currentVersion: String): UpdateInfo = withContext(Dispatchers.IO) {
        Log.d(TAG, "Checking for update, current version: $currentVersion")

        val request = Request.Builder()
            .url(GITHUB_API_URL)
            .header("Accept", "application/vnd.github.v3+json")
            .get()
            .build()

        val response = okHttpClient.newCall(request).execute()

        if (!response.isSuccessful) {
            throw Exception("GitHub API 请求失败: ${response.code}")
        }

        val body = response.body?.string()
            ?: throw Exception("响应体为空")

        val json = JSONObject(body)
        val tagName = json.getString("tag_name") // e.g. "v1.2.0"
        val htmlUrl = json.getString("html_url")
        val releaseNotes = json.optString("body", "暂无更新说明")

        // 去除 tag 前缀 "v" 方便比较
        val latestVersionClean = tagName.removePrefix("v").removePrefix("V")
        val currentVersionClean = currentVersion.removePrefix("v").removePrefix("V")

        val hasUpdate = compareVersions(latestVersionClean, currentVersionClean) > 0

        Log.d(TAG, "Latest: $latestVersionClean, Current: $currentVersionClean, hasUpdate: $hasUpdate")

        UpdateInfo(
            latestVersion = tagName,
            currentVersion = "v$currentVersionClean",
            hasUpdate = hasUpdate,
            releaseUrl = htmlUrl,
            releaseNotes = releaseNotes
        )
    }

    /**
     * 比较两个语义化版本号
     * @return 正数表示 v1 > v2，负数表示 v1 < v2，0 表示相等
     */
    private fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(parts1.size, parts2.size)

        for (i in 0 until maxLen) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) return p1 - p2
        }
        return 0
    }
}
