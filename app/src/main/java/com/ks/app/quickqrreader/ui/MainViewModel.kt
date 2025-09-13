package com.ks.app.quickqrreader.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ks.app.quickqrreader.R
import com.ks.app.quickqrreader.data.DefaultAppRepository // Needed for Factory
import com.ks.app.quickqrreader.domain.HandleQrCodeUseCase
import com.ks.app.quickqrreader.domain.QrCodeProcessingResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MainUiState(
    val isScanning: Boolean = false
)

class MainViewModel(
    private val application: Application, // Still needed for getString
    private val handleQrCodeUseCase: HandleQrCodeUseCase // Injected
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _eventFlow = MutableSharedFlow<ViewEvent>()
    val eventFlow: SharedFlow<ViewEvent> = _eventFlow.asSharedFlow()

    sealed class ViewEvent {
        data class StartActivity(val intent: Intent) : ViewEvent()
        data class ShowToast(val message: String) : ViewEvent()
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
                _eventFlow.emit(ViewEvent.ShowToast(application.getString(R.string.scan_no_data)))
            }
        }
    }

    fun onScanCanceled() {
        _uiState.update { it.copy(isScanning = false) }
        viewModelScope.launch {
            _eventFlow.emit(ViewEvent.ShowToast(application.getString(R.string.scan_canceled)))
        }
    }

    fun onScanFailed(exception: Exception) {
        _uiState.update { it.copy(isScanning = false) }
        viewModelScope.launch {
            _eventFlow.emit(ViewEvent.ShowToast(application.getString(R.string.scan_failed_message, exception.message ?: "Unknown error")))
        }
    }

    private fun processQrCode(qrCode: String) {
        viewModelScope.launch {
            when (val result = handleQrCodeUseCase(qrCode)) { // Use injected use case
                is QrCodeProcessingResult.Success -> {
                    _eventFlow.emit(ViewEvent.StartActivity(result.intent))
                }
                is QrCodeProcessingResult.Error -> {
                    _eventFlow.emit(ViewEvent.ShowToast(application.getString(R.string.cannot_open, result.originalQrCode)))
                }
            }
        }
    }

    companion object {
        fun Factory(application: Application): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                    // Create dependencies here for the actual ViewModel instance
                    val appRepository = DefaultAppRepository(application.applicationContext)
                    val handleQrCodeUseCase = HandleQrCodeUseCase(appRepository)
                    return MainViewModel(application, handleQrCodeUseCase) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class")
            }
        }
    }
}
