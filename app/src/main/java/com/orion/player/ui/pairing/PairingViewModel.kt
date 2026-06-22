package com.orion.player.ui.pairing

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orion.player.data.repository.PairingRepository
import com.orion.player.util.ApiErrorParser.readableMessage
import com.orion.player.util.NetworkDiagnostics
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import retrofit2.HttpException
import javax.inject.Inject

@HiltViewModel
class PairingViewModel @Inject constructor(
    private val pairingRepository: PairingRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<PairingUiState>(PairingUiState.Loading)
    val uiState: StateFlow<PairingUiState> = _uiState.asStateFlow()

    private var hardwareId: String = ""
    private var pairingJob: Job? = null

    init {
        startPairing()
    }

    private fun startPairing() {
        pairingJob?.cancel()
        pairingJob = viewModelScope.launch {
            try {
                _uiState.value = PairingUiState.Loading
                hardwareId = pairingRepository.getHardwareId()
                Log.d(TAG, "startPairing hardwareId=$hardwareId")

                if (pairingRepository.isAlreadyPaired()) {
                    _uiState.value = PairingUiState.Paired
                    return@launch
                }

                val response = pairingRepository.initPairing(hardwareId)

                if (response.isPaired) {
                    _uiState.value = PairingUiState.Error(
                        "Device is registered on the server but not on this player.\n" +
                            "Unpair from CMS, then tap Retry."
                    )
                    return@launch
                }

                val code = response.pairingCode?.takeIf { it.isNotBlank() } ?: run {
                    _uiState.value = PairingUiState.Error("Failed to get pairing code from server")
                    return@launch
                }

                val pairingSecret = response.pairingSecret?.takeIf { it.isNotBlank() } ?: run {
                    _uiState.value = PairingUiState.Error(
                        "Failed to get pairing secret from server.\nTap Retry."
                    )
                    return@launch
                }

                Log.d(TAG, "Showing pairing code=$code")
                _uiState.value = PairingUiState.ShowCode(code)

                pairingRepository.pollPairingStatus(hardwareId, pairingSecret)
                    .catch { e ->
                        Log.e(TAG, "Pairing poll failed after retries", e)
                        _uiState.value = PairingUiState.Error(formatError("GET /player/pairing-status", e))
                    }
                    .collect { status ->
                        if (status.isPaired) {
                            _uiState.value = PairingUiState.Paired
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "startPairing failed", e)
                _uiState.value = PairingUiState.Error(formatError("POST /player/init-pairing", e))
            }
        }
    }

    private fun formatError(endpoint: String, e: Throwable): String = when (e) {
        is HttpException -> "$endpoint failed:\n${e.readableMessage()}"
        else -> NetworkDiagnostics.userMessage(endpoint, e)
    }

    fun retry() {
        pairingRepository.clearPairing()
        startPairing()
    }

    companion object {
        private const val TAG = "OrionPairing"
    }
}

sealed class PairingUiState {
    data object Loading : PairingUiState()
    data class ShowCode(val pairingCode: String) : PairingUiState()
    data object Paired : PairingUiState()
    data class Error(val message: String) : PairingUiState()
}
