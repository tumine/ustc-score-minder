package com.ustc.scoreminder.data.remote

import android.util.Log
import com.ustc.scoreminder.domain.model.Grade
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 成绩页面 HTML 解析器
 */
@Singleton
class GradeParser @Inject constructor() {
    
    companion object {
        private const val TAG = "GradeParser"
    }
    
    /**
     * 解析成绩页面 HTML，提取成绩列表
     */
    fun parseGrades(html: String): List<Grade> {
        val grades = mutableListOf<Grade>()
        
        try {
            val doc: Document = Jsoup.parse(html)
            
            // 尝试解析成绩表格
            // 教务系统成绩页面通常有表格结构
            val gradeRows = doc.select("table.my-table tbody tr, table.grid tbody tr, .el-table__body tr")
            
            Log.d(TAG, "Found ${gradeRows.size} grade rows")
            
            for (row in gradeRows) {
                try {
                    val cells = row.select("td")
                    if (cells.size < 3) continue
                    
                    // 实际 USTC 表格列顺序:
                    // [0]=课程名称+编号, [1]=总学时, [2]=学分, [3]=绩点, [4]=成绩
                    val combined = cells.getOrNull(0)?.text()?.trim() ?: continue
                    val creditStr = cells.getOrNull(2)?.text()?.trim() ?: "0"
                    val gradePointStr = cells.getOrNull(3)?.text()?.trim() ?: ""
                    val score = cells.getOrNull(4)?.text()?.trim() ?: ""
                    val courseType = cells.getOrNull(5)?.text()?.trim()
                    val examType = cells.getOrNull(6)?.text()?.trim()
                    
                    // 拆分合并的课程名称和编号
                    val (courseName, courseId) = splitCourseNameId(combined)
                    
                    val credit = creditStr.toFloatOrNull() ?: 0f
                    val gradePoint = gradePointStr.toFloatOrNull()
                    
                    if (courseName.isNotEmpty()) {
                        grades.add(
                            Grade(
                                courseId = courseId.ifEmpty { courseName },
                                courseName = courseName,
                                credit = credit,
                                score = score,
                                gradePoint = gradePoint,
                                semester = "", // 学期从页面结构中获取
                                courseType = courseType,
                                examType = examType
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing grade row", e)
                }
            }
            
            // 如果表格解析失败，尝试其他解析策略
            if (grades.isEmpty()) {
                Log.d(TAG, "Table parsing yielded no results, trying alternative strategy")
                parseGradesAlternative(doc, grades)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing grades HTML", e)
        }
        
        Log.d(TAG, "Parsed ${grades.size} grades")
        return grades
    }
    
    /**
     * 备用解析策略，适用于不同的页面结构
     */
    private fun parseGradesAlternative(doc: Document, grades: MutableList<Grade>) {
        try {
            // 尝试基于 JavaScript 数据或 JSON 解析
            val scripts = doc.select("script")
            for (script in scripts) {
                val content = script.html()
                if (content.contains("gradeData") || content.contains("scoreList")) {
                    // 解析嵌入的 JSON 数据
                    parseJsonData(content, grades)
                    break
                }
            }
            
            // 尝试解析 data-* 属性
            val dataElements = doc.select("[data-grade], [data-course]")
            for (element in dataElements) {
                val courseId = element.attr("data-course-id")
                val courseName = element.attr("data-course-name")
                val score = element.attr("data-grade")
                val semester = element.attr("data-semester")
                
                if (courseId.isNotEmpty() && courseName.isNotEmpty()) {
                    grades.add(
                        Grade(
                            courseId = courseId,
                            courseName = courseName,
                            credit = 0f,
                            score = score,
                            gradePoint = null,
                            semester = semester,
                            courseType = null,
                            examType = null
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Alternative parsing failed", e)
        }
    }
    
    /**
     * 解析嵌入的 JSON 数据
     */
    private fun parseJsonData(scriptContent: String, grades: MutableList<Grade>) {
        // 简单的正则提取，实际可能需要更复杂的解析
        val pattern = Regex("""courseCode["']?\s*:\s*["']([^"']+)["']""")
        pattern.findAll(scriptContent).forEach { match ->
            Log.d(TAG, "Found course code in script: ${match.groupValues[1]}")
        }
    }
    
    /**
     * 拆分合并的课程名称和编号
     * 如 "数学分析(B1) / MATH1006" -> Pair("数学分析(B1)", "MATH1006")
     * 或 "军事技能MIL1002" -> Pair("军事技能", "MIL1002")
     */
    private fun splitCourseNameId(combined: String): Pair<String, String> {
        // 优先按 " / " 或 "／" 分隔符拆分
        val separators = listOf(" / ", "／", " /", "/ ")
        for (sep in separators) {
            val idx = combined.indexOf(sep)
            if (idx > 0) {
                return Pair(
                    combined.substring(0, idx).trim(),
                    combined.substring(idx + sep.length).trim()
                )
            }
        }
        // 在最后一个CJK字符/标点后拆分课程编号
        // 课程编号格式: 2-5个大写字母 + 3位以上数字 + 可选后缀
        val pattern1 = Regex("""^(.*[\u4e00-\u9fff\u3000-\u303f\uff00-\uffef()])([A-Z]{2,5}\d{3,}\w*)\s*$""")
        pattern1.find(combined)?.let {
            return Pair(it.groupValues[1].trim(), it.groupValues[2].trim())
        }
        // 如果课程名以字母结尾（如"电路A"），匹配中文后跟字母再跟课程编号
        val pattern2 = Regex("""^(.*[\u4e00-\u9fff][A-Za-z]{0,4})([A-Z]{2,5}\d{3,}\w*)\s*$""")
        pattern2.find(combined)?.let {
            return Pair(it.groupValues[1].trim(), it.groupValues[2].trim())
        }
        return Pair(combined, "")
    }
}
