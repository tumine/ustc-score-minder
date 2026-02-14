package com.ustc.scoreminder.data.remote

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 加密工具类
 * 用于处理 CAS 登录的密码加密
 */
object CryptoUtils {
    
    /**
     * 使用 AES/ECB/PKCS5Padding 加密密码
     * USTC CAS 系统使用 AES-ECB 模式（无 IV），密钥来自页面的 login-croypto 元素
     * @param password 明文密码
     * @param key Base64 编码的加密密钥（来自页面的 login-croypto）
     * @return Base64 编码的加密密码
     */
    fun encryptPassword(password: String, key: String): String {
        return try {
            // 解码 Base64 密钥
            val keyBytes = Base64.decode(key, Base64.DEFAULT)
            
            // 创建密钥规格
            val secretKey = SecretKeySpec(keyBytes, "AES")
            
            // 初始化加密器 - ECB 模式无需 IV
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            
            // 加密并返回 Base64 编码结果
            val encryptedBytes = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            // 如果加密失败，返回原始密码（降级处理）
            password
        }
    }
    
    /**
     * 简单的密码加密（密钥 + 密码拼接后编码）
     * 某些 CAS 系统使用这种简单方式
     */
    fun simpleEncrypt(password: String, key: String): String {
        return try {
            val combined = key + password
            Base64.encodeToString(combined.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        } catch (e: Exception) {
            password
        }
    }
}
