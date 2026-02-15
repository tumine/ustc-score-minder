package com.ustc.scoreminder.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onLogout: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState = viewModel.uiState
    var showIntervalDialog by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    
    // Debug: 暂时注释掉自动登出逻辑，排查是否因 isLoggedIn 状态误判导致自动跳转
//    LaunchedEffect(uiState.isLoggedIn) {
//        if (!uiState.isLoggedIn) {
//            onLogout()
//        }
//    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
            // 同步设置
            SettingsSection(title = "同步设置") {
                // 同步间隔
                val intervalText = when {
                    uiState.syncIntervalMs < 60 * 1000L -> "${uiState.syncIntervalMs / 1000} 秒"
                    uiState.syncIntervalMs < 60 * 60 * 1000L -> "${uiState.syncIntervalMs / (60 * 1000)} 分钟"
                    else -> "${uiState.syncIntervalMs / (60 * 60 * 1000)} 小时"
                }
                
                SettingsItem(
                    title = "同步间隔",
                    subtitle = intervalText,
                    onClick = { showIntervalDialog = true }
                )
                
                // 通知开关
                SettingsToggleItem(
                    title = "成绩通知",
                    subtitle = "有新成绩时发送通知",
                    checked = uiState.notificationEnabled,
                    onCheckedChange = viewModel::toggleNotification
                )
            }
            
            HorizontalDivider()

            // 调试信息
            SettingsSection(title = "调试信息") {
                val dateFormat = remember {
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                }

                SettingsItem(
                    title = "立即同步",
                    subtitle = "手动触发一次后台同步",
                    onClick = {
                        viewModel.triggerSyncNow()
                    }
                )

                val lastSyncText = if (uiState.lastSyncTime > 0) {
                    dateFormat.format(java.util.Date(uiState.lastSyncTime))
                } else "从未"

                SettingsItem(
                    title = "上次同步时间",
                    subtitle = lastSyncText,
                    showArrow = false,
                    onClick = { viewModel.loadDebugInfo() }
                )

                var nextSyncText = "未知"
                if (uiState.nextSyncTime > 0) {
                    nextSyncText = dateFormat.format(java.util.Date(uiState.nextSyncTime))
                    val diff = uiState.nextSyncTime - System.currentTimeMillis()
                    if (diff > 0) {
                        val minutes = diff / (1000 * 60)
                        nextSyncText += " (约 ${minutes} 分钟后)"
                    } else {
                        nextSyncText += " (即将执行)"
                    }
                }

                SettingsItem(
                    title = "下次同步时间",
                    subtitle = nextSyncText,
                    showArrow = false,
                    onClick = { viewModel.loadDebugInfo() }
                )

                SettingsItem(
                    title = "上次同步结果",
                    subtitle = uiState.lastSyncResult ?: "无",
                    showArrow = false,
                    onClick = { viewModel.loadDebugInfo() }
                )
            }
            
            HorizontalDivider()
            
            // 账号设置
            SettingsSection(title = "账号") {
                SettingsItem(
                    title = "退出登录",
                    textColor = MaterialTheme.colorScheme.error,
                    onClick = {
                        showLogoutDialog = true
                    }
                )
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            // 版本信息
            Text(
                text = "USTC Score Minder v1.0",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(16.dp)
            )
        }
    }
    
    // 同步间隔选择对话框
    if (showIntervalDialog) {
        IntervalSelectionDialog(
            currentIntervalMs = uiState.syncIntervalMs,
            onSelect = { 
                viewModel.updateSyncInterval(it)
                showIntervalDialog = false
            },
            onDismiss = { showIntervalDialog = false }
        )
    }

    // 退出登录确认对话框
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("确认退出") },
            text = { Text("退出登录将清除所有本地保存的成绩数据和凭证。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutDialog = false
                        viewModel.logout()
                        onLogout()
                    }
                ) {
                    Text("退出")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 同步加载弹窗
    if (uiState.isSyncing) {
        AlertDialog(
            onDismissRequest = { /* 禁止点击外部关闭 */ },
            title = { Text("正在同步") },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        strokeWidth = 3.dp
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("正在获取最新成绩...")
                }
            },
            confirmButton = {}
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp)
        )
        content()
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

@Composable
private fun SettingsItem(
    title: String,
    subtitle: String? = null,
    textColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    showArrow: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = textColor
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (showArrow) {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsToggleItem(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun IntervalSelectionDialog(
    currentIntervalMs: Long,
    onSelect: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val shortIntervals = listOf(
        30 * 1000L,       // 30秒
        60 * 1000L,       // 1分钟
        5 * 60 * 1000L,   // 5分钟
        10 * 60 * 1000L   // 10分钟
    )
    
    val standardIntervals = listOf(
        15 * 60 * 1000L,  // 15分钟
        30 * 60 * 1000L,  // 30分钟
        60 * 60 * 1000L,  // 1小时
        120 * 60 * 1000L, // 2小时
        240 * 60 * 1000L  // 4小时
    )
    
    // 是否显示高级选项页面
    var isAdvancedPage by remember { mutableStateOf(false) }
    
    AlertDialog(
        onDismissRequest = {
            if (isAdvancedPage && !shortIntervals.contains(currentIntervalMs)) {
                isAdvancedPage = false
            } else {
                onDismiss()
            }
        },
        title = { 
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isAdvancedPage) {
                    IconButton(onClick = { isAdvancedPage = false }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Text("调试选项")
                } else {
                    Text("选择同步间隔")
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                if (isAdvancedPage) {
                    // 短间隔选项
                    shortIntervals.forEach { interval ->
                        IntervalOption(
                            interval = interval,
                            isSelected = interval == currentIntervalMs,
                            onSelect = onSelect
                        )
                    }
                } else {
                    // 标准间隔选项
                    standardIntervals.forEach { interval ->
                        IntervalOption(
                            interval = interval,
                            isSelected = interval == currentIntervalMs,
                            onSelect = onSelect
                        )
                    }
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    
                    // 更多选项按钮 - 跳转到新页面
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isAdvancedPage = true }
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "更多选项 (调试用短间隔)",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun IntervalOption(
    interval: Long,
    isSelected: Boolean,
    onSelect: (Long) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(interval) }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = isSelected,
            onClick = { onSelect(interval) }
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = when {
                interval < 60 * 1000L -> "${interval / 1000} 秒"
                interval < 60 * 60 * 1000L -> "${interval / (60 * 1000)} 分钟"
                else -> "${interval / (60 * 60 * 1000)} 小时"
            }
        )
    }
}
