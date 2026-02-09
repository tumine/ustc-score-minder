package com.ustc.scoreminder.domain.usecase

import com.ustc.scoreminder.data.local.CredentialsManager
import com.ustc.scoreminder.data.remote.JwAuthenticator
import javax.inject.Inject

/**
 * 登录用例
 */
class LoginUseCase @Inject constructor(
    private val authenticator: JwAuthenticator,
    private val credentialsManager: CredentialsManager
) {
    /**
     * 执行登录
     * @param saveCredentials 是否保存凭证
     */
    suspend operator fun invoke(
        username: String, 
        password: String,
        saveCredentials: Boolean = true
    ): Result<Unit> {
        if (username.isBlank() || password.isBlank()) {
            return Result.failure(Exception("用户名和密码不能为空"))
        }
        
        val result = authenticator.login(username, password)
        
        return when (result) {
            is JwAuthenticator.LoginResult.Success -> {
                if (saveCredentials) {
                    credentialsManager.saveCredentials(username, password)
                }
                Result.success(Unit)
            }
            is JwAuthenticator.LoginResult.Error -> {
                Result.failure(Exception(result.message))
            }
            is JwAuthenticator.LoginResult.NeedLogin -> {
                Result.failure(Exception("登录失败"))
            }
        }
    }
    
    /**
     * 检查是否已保存凭证
     */
    fun hasCredentials(): Boolean = credentialsManager.hasCredentials()
    
    /**
     * 登出（清除凭证）
     */
    fun logout() {
        credentialsManager.clearCredentials()
    }
}
