package com.ustc.scoreminder.domain.model

/**
 * 成绩领域模型
 */
data class Grade(
    val courseId: String,       // 完整课堂号，如 MATH1006.05
    val courseName: String,     // 课程名称
    val credit: Float,          // 学分
    val score: String,          // 成绩
    val gradePoint: Float?,     // 绩点
    val semester: String,       // 学期
    val courseType: String?,    // 课程类型
    val examType: String?       // 考试类型
) {
    /**
     * 获取课程号（不含课堂号）
     */
    val courseCode: String
        get() = courseId.substringBefore(".")
    
    /**
     * 获取课堂号
     */
    val classCode: String
        get() = courseId.substringAfter(".", "")
}
