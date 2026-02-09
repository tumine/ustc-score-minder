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
            Result.failure(Exception(result.errorMessage ?: "同步失败"))
        }
    }
    
    data class SyncResult(
        val newGrades: List<Grade>,
        val removedGrades: List<Grade>,
        val hasChanges: Boolean
    )
}
