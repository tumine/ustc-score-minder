package com.ustc.scoreminder.data.remote

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
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
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(@ApplicationContext context: Context): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        
        return OkHttpClient.Builder()
            .cookieJar(InMemoryCookieJar())
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
 * 内存中的 Cookie 管理器
 */
class InMemoryCookieJar : CookieJar {
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
    }
    
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val result = mutableListOf<Cookie>()
        val host = url.host
        
        // 获取当前域名的 cookies
        cookieStore[host]?.let { result.addAll(it) }
        
        // 获取父域名的 cookies (e.g., .ustc.edu.cn)
        val parts = host.split(".")
        if (parts.size >= 2) {
            val parentDomain = parts.takeLast(2).joinToString(".")
            cookieStore[parentDomain]?.let { result.addAll(it) }
            cookieStore[".$parentDomain"]?.let { result.addAll(it) }
        }
        
        return result.filter { !it.expiresAt.let { exp -> exp < System.currentTimeMillis() } }
    }
    
    fun clear() {
        cookieStore.clear()
    }
}
