package com.orion.player.ui.cache

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orion.player.data.cache.CacheDebugInfo
import com.orion.player.data.repository.ContentCacheRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class CacheDebugUiState {
    data object Loading : CacheDebugUiState()
    data class Ready(val info: CacheDebugInfo) : CacheDebugUiState()
    data class Error(val message: String) : CacheDebugUiState()
}

@HiltViewModel
class CacheDebugViewModel @Inject constructor(
    private val contentCacheRepository: ContentCacheRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<CacheDebugUiState>(CacheDebugUiState.Loading)
    val uiState: StateFlow<CacheDebugUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = CacheDebugUiState.Loading
            runCatching { contentCacheRepository.getCacheDebugInfo() }
                .onSuccess { info ->
                    _uiState.value = CacheDebugUiState.Ready(info)
                }
                .onFailure { error ->
                    _uiState.value = CacheDebugUiState.Error(
                        error.message ?: "Failed to load cache info"
                    )
                }
        }
    }
}
