package com.ustc.scoreminder.data.remote

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 加密工具类
 * 用于处理 CAS 登录的密码加密
 */
object CryptoUtils {
    
    /**
     * 使用 AES/CBC/PKCS5Padding 加密密码
     * @param password 明文密码
     * @param key Base64 编码的加密密钥（来自页面的 login-croypto）
     * @return Base64 编码的加密密码
     */
    fun encryptPassword(password: String, key: String): String {
        return try {
            // 解码 Base64 密钥
            val keyBytes = Base64.decode(key, Base64.DEFAULT)
            
            // 创建密钥规格，使用密钥本身作为 IV（常见的简单实现）
            val secretKey = SecretKeySpec(keyBytes, "AES")
            
            // 随机 IV 或使用密钥前 16 字节
            val iv = if (keyBytes.size >= 16) {
                keyBytes.copyOf(16)
            } else {
                ByteArray(16) // 全零 IV
            }
            val ivSpec = IvParameterSpec(iv)
            
            // 初始化加密器
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec)
            
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
