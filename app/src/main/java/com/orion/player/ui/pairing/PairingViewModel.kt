package com.orion.player.ui.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orion.player.data.repository.PairingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel managing the pairing screen state machine:
 * Loading → ShowCode → Polling → Paired / Error
 */
@HiltViewModel
class PairingViewModel @Inject constructor(
    private val pairingRepository: PairingRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<PairingUiState>(PairingUiState.Loading)
    val uiState: StateFlow<PairingUiState> = _uiState.asStateFlow()

    private var hardwareId: String = ""

    init {
        startPairing()
    }

    private fun startPairing() {
        viewModelScope.launch {
            try {
                _uiState.value = PairingUiState.Loading

                // Get or create hardware ID
                hardwareId = pairingRepository.getHardwareId()

                // Check if already paired
                if (pairingRepository.isAlreadyPaired()) {
                    _uiState.value = PairingUiState.Paired
                    return@launch
                }

                // Init pairing — get the code
                val response = pairingRepository.initPairing(hardwareId)

                if (response.isPaired) {
                    _uiState.value = PairingUiState.Paired
                    return@launch
                }

                val code = response.pairingCode ?: run {
                    _uiState.value = PairingUiState.Error("Failed to get pairing code")
                    return@launch
                }

                _uiState.value = PairingUiState.ShowCode(code)

                // Start polling for pairing completion
                pairingRepository.pollPairingStatus(hardwareId)
                    .catch { e ->
                        _uiState.value = PairingUiState.Error(
                            e.message ?: "Pairing failed. Please check your connection."
                        )
                    }
                    .collect { status ->
                        if (status.isPaired) {
                            _uiState.value = PairingUiState.Paired
                        }
                        // Non-paired statuses continue polling (flow keeps emitting)
                    }

            } catch (e: Exception) {
                _uiState.value = PairingUiState.Error(
                    e.message ?: "Failed to initialize pairing. Please check your connection."
                )
            }
        }
    }

    /**
     * Retry pairing from the beginning (called from error state).
     */
    fun retry() {
        startPairing()
    }
}

/**
 * Sealed class representing all possible pairing screen states.
 */
sealed class PairingUiState {
    data object Loading : PairingUiState()
    data class ShowCode(val pairingCode: String) : PairingUiState()
    data object Paired : PairingUiState()
    data class Error(val message: String) : PairingUiState()
}
