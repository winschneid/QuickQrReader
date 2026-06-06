package com.ks.app.quickqrreader.ui

import android.app.Application
import android.content.Intent
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ks.app.quickqrreader.R
import com.ks.app.quickqrreader.data.DefaultAppRepository
import com.ks.app.quickqrreader.domain.HandleQrCodeUseCase
import com.ks.app.quickqrreader.domain.QrCodeProcessingResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MainUiState(
    val isScanning: Boolean = false
)

class MainViewModel(
    private val handleQrCodeUseCase: HandleQrCodeUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    // Channel を使用してイベントをバッファリング。
    // SharedFlow(replay=0) だと GMS スキャナー表示中（MainActivity が STOPPED）に
    // emit されたイベントがコレクター停止中に消えるため。
    private val _eventChannel = Channel<ViewEvent>(Channel.BUFFERED)
    val eventFlow = _eventChannel.receiveAsFlow()

    sealed class ViewEvent {
        data class StartActivity(val intent: Intent) : ViewEvent()
        data class ShowToast(@StringRes val messageRes: Int, val formatArg: String? = null) : ViewEvent()
    }

    fun onScanStarted() {
        _uiState.update { it.copy(isScanning = true) }
    }

    fun onScanSuccess(qrCodeValue: String?) {
        _uiState.update { it.copy(isScanning = false) }
        if (qrCodeValue != null) {
            processQrCode(qrCodeValue)
        } else {
            viewModelScope.launch {
                _eventChannel.send(ViewEvent.ShowToast(R.string.scan_no_data))
            }
        }
    }

    fun onScanCanceled() {
        _uiState.update { it.copy(isScanning = false) }
        viewModelScope.launch {
            _eventChannel.send(ViewEvent.ShowToast(R.string.scan_canceled))
        }
    }

    fun onScanFailed(exception: Exception) {
        _uiState.update { it.copy(isScanning = false) }
        viewModelScope.launch {
            _eventChannel.send(
                ViewEvent.ShowToast(R.string.scan_failed_message, exception.message ?: "Unknown error")
            )
        }
    }

    private fun processQrCode(qrCode: String) {
        viewModelScope.launch {
            when (val result = handleQrCodeUseCase(qrCode)) {
                is QrCodeProcessingResult.Success -> {
                    _eventChannel.send(ViewEvent.StartActivity(result.intent))
                }
                is QrCodeProcessingResult.Error -> {
                    _eventChannel.send(ViewEvent.ShowToast(R.string.cannot_open, result.originalQrCode))
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        _eventChannel.close()
    }

    companion object {
        // Manual Factory pattern used instead of Hilt, as this app has no other DI requirements.
        fun Factory(application: Application): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                    val appRepository = DefaultAppRepository(application.applicationContext)
                    val handleQrCodeUseCase = HandleQrCodeUseCase(appRepository)
                    return MainViewModel(handleQrCodeUseCase) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class")
            }
        }
    }
}
