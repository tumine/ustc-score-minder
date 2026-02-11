package com.ustc.scoreminder.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
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
                SettingsItem(
                    title = "同步间隔",
                    subtitle = "${uiState.syncIntervalMinutes} 分钟",
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
            currentInterval = uiState.syncIntervalMinutes,
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
            title = { Text("退出登录") },
            text = { Text("确定要退出登录吗？退出后需要重新登录才能查看成绩。") },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutDialog = false
                    viewModel.logout()
                    onLogout()
                }) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
        )
        content()
    }
}

@Composable
private fun SettingsItem(
    title: String,
    subtitle: String? = null,
    textColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
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
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
    currentInterval: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val intervals = listOf(15, 30, 60, 120, 240)
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择同步间隔") },
        text = {
            Column {
                intervals.forEach { interval ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(interval) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = interval == currentInterval,
                            onClick = { onSelect(interval) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when (interval) {
                                15 -> "15 分钟"
                                30 -> "30 分钟"
                                60 -> "1 小时"
                                120 -> "2 小时"
                                240 -> "4 小时"
                                else -> "$interval 分钟"
                            }
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
