package com.ustc.scoreminder.data.remote

import android.content.Context
import android.webkit.CookieManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * 网络相关的 Hilt 模块
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    
    /**
     * 提供带 Cookie 管理的 OkHttpClient
     * 使用 WebViewSyncCookieJar 来同步 WebView 和 OkHttp 的 cookies
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(@ApplicationContext context: Context): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        
        return OkHttpClient.Builder()
            .cookieJar(WebViewSyncCookieJar())
            .addInterceptor(loggingInterceptor)
            .addInterceptor { chain ->
                val original = chain.request()
                val request = original.newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .build()
                chain.proceed(request)
            }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}

/**
 * 与 WebView CookieManager 同步的 Cookie 管理器
 * 这使得 WebView 登录后的 cookies 可以被 OkHttp 使用
 */
class WebViewSyncCookieJar : CookieJar {
    private val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()
    
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val host = url.host
        cookieStore.getOrPut(host) { mutableListOf() }.apply {
            // 移除同名的旧 cookie
            cookies.forEach { newCookie ->
                removeAll { it.name == newCookie.name }
            }
            addAll(cookies)
        }
        
        // 同时保存到 WebView CookieManager
        try {
            val cookieManager = CookieManager.getInstance()
            cookies.forEach { cookie ->
                cookieManager.setCookie(url.toString(), cookie.toString())
            }
            cookieManager.flush()
        } catch (e: Exception) {
            // 忽略 CookieManager 错误
        }
    }
    
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val result = mutableListOf<Cookie>()
        val host = url.host
        
        // 首先尝试从 WebView CookieManager 获取 cookies
        try {
            val webViewCookies = syncFromWebView(url)
            result.addAll(webViewCookies)
        } catch (e: Exception) {
            // 忽略错误，继续使用内存中的 cookies
        }
        
        // 如果 WebView 没有 cookies，使用内存中的
        if (result.isEmpty()) {
            // 获取当前域名的 cookies
            cookieStore[host]?.let { result.addAll(it) }
            
            // 获取父域名的 cookies (e.g., .ustc.edu.cn)
            val parts = host.split(".")
            if (parts.size >= 2) {
                val parentDomain = parts.takeLast(2).joinToString(".")
                cookieStore[parentDomain]?.let { result.addAll(it) }
                cookieStore[".$parentDomain"]?.let { result.addAll(it) }
            }
        }
        
        return result.filter { !it.expiresAt.let { exp -> exp < System.currentTimeMillis() } }
    }
    
    /**
     * 从 WebView CookieManager 获取 cookies 并转换为 OkHttp Cookie
     */
    private fun syncFromWebView(url: HttpUrl): List<Cookie> {
        val cookies = mutableListOf<Cookie>()
        
        try {
            val cookieManager = CookieManager.getInstance()
            val cookieString = cookieManager.getCookie(url.toString()) ?: return cookies
            
            // 解析 cookie 字符串 "name1=value1; name2=value2"
            cookieString.split(";").forEach { cookiePart ->
                val parts = cookiePart.trim().split("=", limit = 2)
                if (parts.size == 2) {
                    val name = parts[0].trim()
                    val value = parts[1].trim()
                    
                    if (name.isNotEmpty()) {
                        Cookie.Builder()
                            .domain(url.host)
                            .path("/")
                            .name(name)
                            .value(value)
                            .build()
                            .let { cookies.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            // 忽略解析错误
        }
        
        return cookies
    }
    
    fun clear() {
        cookieStore.clear()
        try {
            CookieManager.getInstance().removeAllCookies(null)
        } catch (e: Exception) {
            // 忽略错误
        }
    }
}
