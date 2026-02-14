package com.ustc.scoreminder.ui.grades

import android.util.Log
import android.webkit.CookieManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ustc.scoreminder.data.local.CredentialsManager
import com.ustc.scoreminder.data.repository.GradeRepository
import com.ustc.scoreminder.domain.model.Grade
import com.ustc.scoreminder.domain.usecase.SyncGradesUseCase
import com.ustc.scoreminder.domain.usecase.SyncGradesUseCase.AuthenticationException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GradeListViewModel @Inject constructor(
    private val gradeRepository: GradeRepository,
    private val syncGradesUseCase: SyncGradesUseCase,
    private val credentialsManager: CredentialsManager
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(GradeListUiState())
    val uiState: StateFlow<GradeListUiState> = _uiState.asStateFlow()
    
    private val _grades = MutableStateFlow<List<Grade>>(emptyList())
    val grades: StateFlow<List<Grade>> = _grades.asStateFlow()
    
    // 所有可用学期（按降序排列）
    val allSemesters: StateFlow<List<String>> = _grades.map { grades ->
        grades.map { it.semester }.distinct().sortedDescending()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    
    // 已选中的学期（空集合 = 显示全部）
    private val _selectedSemesters = MutableStateFlow<Set<String>>(emptySet())
    val selectedSemesters: StateFlow<Set<String>> = _selectedSemesters.asStateFlow()
    
    // 按学期分组的成绩（保留用于向后兼容）
    val gradesBySemester: StateFlow<Map<String, List<Grade>>> = _grades.map { grades ->
        grades.groupBy { it.semester }
            .toSortedMap(compareByDescending { it })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
    
    // 根据筛选条件过滤后的成绩（按学期分组）
    val filteredGradesBySemester: StateFlow<Map<String, List<Grade>>> = combine(
        _grades, _selectedSemesters
    ) { grades, selected ->
        val filtered = if (selected.isEmpty()) grades else grades.filter { it.semester in selected }
        filtered.groupBy { it.semester }
            .toSortedMap(compareByDescending { it })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
    
    init {
        // 观察本地成绩变化
        viewModelScope.launch {
            gradeRepository.getAllGrades().collect { grades ->
                _grades.value = grades
            }
        }
        
        // 检查是否需要重新登录
        checkNeedsReLogin()
        
        // 启动时同步一次
        syncGrades()
    }
    
    fun syncGrades() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, errorMessage = null) }
            
            syncGradesUseCase()
                .onSuccess { result ->
                    _uiState.update { 
                        it.copy(
                            isRefreshing = false,
                            lastSyncTime = System.currentTimeMillis(),
                            newGradesCount = result.newGrades.size,
                            needsReLogin = false,
                            showWebViewLogin = false,
                            showCredentialDialog = false
                        ) 
                    }
                }
                .onFailure { e ->
                    val isAuthError = e is AuthenticationException
                    if (isAuthError) {
                        Log.w("GradeListViewModel", "Auth error during sync: ${e.message}")
                    }
                    _uiState.update { 
                        it.copy(
                            isRefreshing = false,
                            errorMessage = e.message ?: "同步失败",
                            needsReLogin = isAuthError,
                            // 认证错误时，先尝试 WebView 登录流程
                            showWebViewLogin = isAuthError && credentialsManager.hasCredentials(),
                            // 如果没有保存凭证，直接弹出凭证输入框
                            showCredentialDialog = isAuthError && !credentialsManager.hasCredentials()
                        ) 
                    }
                }
        }
    }
    
    fun clearNewGradesAlert() {
        _uiState.update { it.copy(newGradesCount = 0) }
    }
    
    /**
     * 检查是否需要重新登录（从 CredentialsManager 读取标志）
     */
    private fun checkNeedsReLogin() {
        if (credentialsManager.getNeedsReLogin()) {
            _uiState.update { 
                it.copy(
                    needsReLogin = true,
                    // 有凭证时先走 WebView 登录流程
                    showWebViewLogin = credentialsManager.hasCredentials(),
                    showCredentialDialog = !credentialsManager.hasCredentials()
                ) 
            }
        }
    }
    
    /**
     * 获取保存的凭证，用于 WebView 自动填充
     */
    fun getSavedCredentials(): Pair<String, String>? {
        val u = credentialsManager.getUsername()
        val p = credentialsManager.getPassword()
        return if (u != null && p != null) u to p else null
    }
    
    /**
     * WebView 重新登录成功回调
     */
    fun onWebViewReLoginSuccess(username: String, password: String) {
        Log.d("GradeListViewModel", "WebView re-login success")
        // 更新凭证（以防用户在 WebView 中输入了新的凭证）
        if (username.isNotBlank() && password.isNotBlank()) {
            credentialsManager.saveCredentials(username, password)
        }
        credentialsManager.setNeedsReLogin(false)
        _uiState.update { 
            it.copy(
                needsReLogin = false, 
                showWebViewLogin = false, 
                showCredentialDialog = false,
                errorMessage = null
            ) 
        }
        // 重新登录成功后重新同步
        syncGrades()
    }
    
    /**
     * WebView 登录出错回调（密码错误等）
     * 转为显示凭证输入对话框
     */
    fun onWebViewReLoginError() {
        Log.w("GradeListViewModel", "WebView re-login error, showing credential dialog")
        credentialsManager.setNeedsReLogin(true)
        _uiState.update { 
            it.copy(
                showWebViewLogin = false, 
                showCredentialDialog = true,
                errorMessage = "用户名或密码错误，请确认后重新输入"
            ) 
        }
    }
    
    /**
     * WebView 登录取消
     */
    fun onWebViewReLoginCancel() {
        _uiState.update { 
            it.copy(showWebViewLogin = false, needsReLogin = false) 
        }
    }
    
    /**
     * 用户输入新凭证后保存并通过 WebView 重新登录
     */
    fun onCredentialsReEntered(username: String, password: String) {
        Log.d("GradeListViewModel", "Credentials re-entered, saving and starting WebView login")
        credentialsManager.saveCredentials(username, password)
        credentialsManager.setNeedsReLogin(false)
        _uiState.update { 
            it.copy(
                showCredentialDialog = false, 
                showWebViewLogin = true,
                errorMessage = null
            ) 
        }
    }
    
    /**
     * 用户取消重新登录
     */
    fun dismissReLogin() {
        _uiState.update { 
            it.copy(
                needsReLogin = false, 
                showWebViewLogin = false, 
                showCredentialDialog = false
            ) 
        }
    }
    
    /**
     * 切换某个学期的选中状态
     */
    fun toggleSemester(semester: String) {
        _selectedSemesters.update { current ->
            if (semester in current) current - semester else current + semester
        }
    }
    
    /**
     * 选中所有学期
     */
    fun selectAllSemesters() {
        _selectedSemesters.value = allSemesters.value.toSet()
    }
    
    /**
     * 清空选择（显示全部）
     */
    fun clearSemesterSelection() {
        _selectedSemesters.value = emptySet()
    }
    
}

data class GradeListUiState(
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val lastSyncTime: Long? = null,
    val newGradesCount: Int = 0,
    val needsReLogin: Boolean = false,
    val showWebViewLogin: Boolean = false,
    val showCredentialDialog: Boolean = false
)
