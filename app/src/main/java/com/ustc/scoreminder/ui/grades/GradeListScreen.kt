package com.ustc.scoreminder.ui.grades

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ustc.scoreminder.domain.model.Grade
import com.ustc.scoreminder.ui.login.WebViewLoginScreen

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GradeListScreen(
    onNavigateToSettings: () -> Unit,
    viewModel: GradeListViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val filteredGradesBySemester by viewModel.filteredGradesBySemester.collectAsState()
    val allSemesters by viewModel.allSemesters.collectAsState()
    val selectedSemesters by viewModel.selectedSemesters.collectAsState()
    var showFilter by remember { mutableStateOf(false) }
    
    // 当需要重新登录时，先尝试 WebView 登录流程（使用保存的凭证自动填充）
    if (uiState.showWebViewLogin) {
        WebViewLoginScreen(
            onLoginSuccess = { username, password ->
                viewModel.onWebViewReLoginSuccess(username, password)
            },
            onLoginCancel = {
                viewModel.onWebViewReLoginCancel()
            },
            onLoginError = {
                // WebView 登录失败（密码错误），转为显示凭证输入对话框
                viewModel.onWebViewReLoginError()
            },
            credentials = remember { viewModel.getSavedCredentials() }
        )
        return // WebView 登录界面覆盖整个屏幕
    }
    
    // WebView 登录失败后，显示凭证输入对话框让用户重新输入
    if (uiState.showCredentialDialog) {
        ReLoginDialog(
            errorMessage = uiState.errorMessage,
            onConfirm = { username, password ->
                viewModel.onCredentialsReEntered(username, password)
            },
            onDismiss = {
                viewModel.dismissReLogin()
            }
        )
    }



    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的成绩") },
                actions = {
                    // 设置按钮
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text("设置") } },
                        state = rememberTooltipState()
                    ) {
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "设置")
                        }
                    }
                    // 筛选按钮
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text("筛选学期") } },
                        state = rememberTooltipState()
                    ) {
                        IconButton(onClick = { showFilter = !showFilter }) {
                            BadgedBox(
                                badge = {
                                    if (selectedSemesters.isNotEmpty()) {
                                        Badge { Text("${selectedSemesters.size}") }
                                    }
                                }
                            ) {
                                Icon(Icons.Default.FilterList, contentDescription = "筛选学期")
                            }
                        }
                    }
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text("刷新成绩") } },
                        state = rememberTooltipState()
                    ) {
                        IconButton(onClick = viewModel::syncGrades) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新成绩")
                        }
                    }

                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 学期筛选面板
            AnimatedVisibility(
                visible = showFilter,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                SemesterFilterPanel(
                    allSemesters = allSemesters,
                    selectedSemesters = selectedSemesters,
                    onToggleSemester = viewModel::toggleSemester,
                    onSelectAll = viewModel::selectAllSemesters,
                    onClearSelection = viewModel::clearSemesterSelection
                )
            }

            PullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = { viewModel.syncGrades() },
                modifier = Modifier.fillMaxSize()
            ) {
                if (filteredGradesBySemester.isEmpty() && !uiState.isRefreshing) {
                    EmptyState()
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 错误提示
                        uiState.errorMessage?.let { error ->
                            item {
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = error,
                                        modifier = Modifier.padding(16.dp),
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                        
                        // 新成绩提示
                        if (uiState.newGradesCount > 0) {
                            item {
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                                    ),
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = viewModel::clearNewGradesAlert
                                ) {
                                    Text(
                                        text = "🎉 发现 ${uiState.newGradesCount} 门新成绩！",
                                        modifier = Modifier.padding(16.dp),
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        
                        // 按学期分组显示成绩
                        filteredGradesBySemester.forEach { (semester, grades) ->
                            item {
                                SemesterHeader(semester = semester)
                            }
                            
                            items(grades, key = { it.courseId }) { grade ->
                                GradeItem(grade = grade)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 学期筛选面板
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SemesterFilterPanel(
    allSemesters: List<String>,
    selectedSemesters: Set<String>,
    onToggleSemester: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "学期筛选",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onSelectAll) {
                        Text("全选", style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = onClearSelection) {
                        Text("全部显示", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(4.dp))
            
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                allSemesters.forEach { semester ->
                    FilterChip(
                        selected = selectedSemesters.contains(semester),
                        onClick = { onToggleSemester(semester) },
                        label = { Text(semester, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SemesterHeader(semester: String) {
    Text(
        text = semester,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    )
}

/**
 * 成绩项：框外显示"课程编号 课程名称"，框内左侧显示学分和绩点，右侧显示成绩
 */
@Composable
private fun GradeItem(grade: Grade) {
    val isBinaryGrade = isBinaryGradeScore(grade.score)

    Column(modifier = Modifier.fillMaxWidth()) {
        // 框外：课程编号 + 课程名称
        Text(
            text = "${grade.courseId}  ${grade.courseName}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        // 框内
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧：学分 和 绩点
                Column {
                    Text(
                        text = "学分: ${grade.credit}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // 二分制课程不显示绩点
                    if (!isBinaryGrade && grade.gradePoint != null) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "绩点: ${grade.gradePoint}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 右侧：成绩
                Text(
                    text = grade.score,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = getScoreColor(grade.score)
                )
            }
        }
    }
}

/**
 * 判断是否为二分制记载的成绩
 */
private fun isBinaryGradeScore(score: String): Boolean {
    val binaryScores = setOf(
        "通过", "不通过",
        "合格", "不合格",
        "P", "NP",
        "pass", "fail",
        "PASS", "FAIL"
    )
    return score.trim() in binaryScores
}

@Composable
private fun getScoreColor(score: String): androidx.compose.ui.graphics.Color {
    val numScore = score.toFloatOrNull()
    return when {
        numScore == null -> MaterialTheme.colorScheme.onSurface
        numScore >= 90 -> MaterialTheme.colorScheme.primary
        numScore >= 80 -> MaterialTheme.colorScheme.tertiary
        numScore >= 60 -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.error
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "📚",
                style = MaterialTheme.typography.displayLarge
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "暂无成绩记录",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "下拉刷新以同步成绩",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 重新登录对话框
 * 当登录状态过期且密码错误时，弹出此对话框要求用户重新输入凭证
 */
@Composable
private fun ReLoginDialog(
    errorMessage: String?,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    // 复用 LoginScreen 中的 CredentialInputDialog，但添加错误提示
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("登录状态已过期") },
        text = {
            Column {
                // 显示错误原因
                errorMessage?.let { msg ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
                
                Text(
                    text = "请重新输入统一身份认证账号密码",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("学号/工号") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) 
                        androidx.compose.ui.text.input.VisualTransformation.None 
                    else 
                        androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) 
                                    Icons.Filled.Visibility
                                else 
                                    Icons.Filled.VisibilityOff,
                                contentDescription = if (passwordVisible) "隐藏密码" else "显示密码"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "您的账号密码将加密存储于本地，仅用于自动登录教务系统。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(username, password) },
                enabled = username.isNotBlank() && password.isNotBlank()
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("稍后")
            }
        }
    )
}
