package com.ustc.scoreminder.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 成绩数据库实体
 * 使用完整课堂号（如 MATH1006.05）作为唯一标识
 */
@Entity(tableName = "grades")
data class GradeEntity(
    @PrimaryKey
    val courseId: String,       // 完整课堂号，如 MATH1006.05
    val courseName: String,     // 课程名称
    val credit: Float,          // 学分
    val score: String,          // 成绩（可能是数字或等级）
    val gradePoint: Float?,     // 绩点
    val semester: String,       // 学期，如 2024-2025-1
    val courseType: String?,    // 课程类型（必修/选修等）
    val examType: String?,      // 考试类型（考试/考查）
    val createdAt: Long = System.currentTimeMillis()
)
