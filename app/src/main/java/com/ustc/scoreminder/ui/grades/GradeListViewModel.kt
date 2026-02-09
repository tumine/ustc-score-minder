package com.ustc.scoreminder.ui.grades

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ustc.scoreminder.data.repository.GradeRepository
import com.ustc.scoreminder.domain.model.Grade
import com.ustc.scoreminder.domain.usecase.SyncGradesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GradeListViewModel @Inject constructor(
    private val gradeRepository: GradeRepository,
    private val syncGradesUseCase: SyncGradesUseCase
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(GradeListUiState())
    val uiState: StateFlow<GradeListUiState> = _uiState.asStateFlow()
    
    private val _grades = MutableStateFlow<List<Grade>>(emptyList())
    val grades: StateFlow<List<Grade>> = _grades.asStateFlow()
    
    // 按学期分组的成绩
    val gradesBySemester: StateFlow<Map<String, List<Grade>>> = _grades.map { grades ->
        grades.groupBy { it.semester }
            .toSortedMap(compareByDescending { it })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
    
    init {
        // 观察本地成绩变化
        viewModelScope.launch {
            gradeRepository.getAllGrades().collect { grades ->
                _grades.value = grades
            }
        }
        
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
                            newGradesCount = result.newGrades.size
                        ) 
                    }
                }
                .onFailure { e ->
                    _uiState.update { 
                        it.copy(
                            isRefreshing = false,
                            errorMessage = e.message ?: "同步失败"
                        ) 
                    }
                }
        }
    }
    
    fun clearNewGradesAlert() {
        _uiState.update { it.copy(newGradesCount = 0) }
    }
}

data class GradeListUiState(
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val lastSyncTime: Long? = null,
    val newGradesCount: Int = 0
)
