package com.ustc.scoreminder.domain.usecase

import com.ustc.scoreminder.data.repository.GradeRepository
import com.ustc.scoreminder.domain.model.Grade
import javax.inject.Inject

/**
 * 同步成绩用例
 */
class SyncGradesUseCase @Inject constructor(
    private val gradeRepository: GradeRepository
) {
    /**
     * 执行成绩同步
     * @return 同步结果，包含新增和删除的成绩
     */
    suspend operator fun invoke(): Result<SyncResult> {
        val result = gradeRepository.syncGrades()
        
        return if (result.success) {
            Result.success(
                SyncResult(
                    newGrades = result.newGrades,
                    removedGrades = result.removedGrades,
                    hasChanges = result.newGrades.isNotEmpty() || result.removedGrades.isNotEmpty()
                )
            )
        } else {
            val exception = if (result.isAuthError) {
                AuthenticationException(result.errorMessage ?: "认证失败，请重新登录")
            } else {
                Exception(result.errorMessage ?: "同步失败")
            }
            Result.failure(exception)
        }
    }
    
    data class SyncResult(
        val newGrades: List<Grade>,
        val removedGrades: List<Grade>,
        val hasChanges: Boolean
    )
    
    /**
     * 认证异常，用于区分认证错误和其他错误
     * 当检测到此异常时，UI 层应提示用户重新输入凭证
     */
    class AuthenticationException(message: String) : Exception(message)
}
