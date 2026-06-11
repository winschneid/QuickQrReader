package com.ks.app.quickqrreader.ui

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ks.app.quickqrreader.R
import com.ks.app.quickqrreader.data.DefaultAppRepository
import com.ks.app.quickqrreader.data.ScanHistoryRepository
import com.ks.app.quickqrreader.data.SharedPrefsScanHistoryRepository
import com.ks.app.quickqrreader.domain.HandleQrCodeUseCase
import com.ks.app.quickqrreader.domain.QrCodeProcessingResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MainUiState(
    val isScanning: Boolean = false,
    val lastScannedValue: String? = null,
    val history: List<String> = emptyList(),
    val moduleError: String? = null
)

class MainViewModel(
    private val handleQrCodeUseCase: HandleQrCodeUseCase,
    private val scanHistoryRepository: ScanHistoryRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    // Channel を使用してイベントをバッファリング。
    // SharedFlow(replay=0) だと GMS スキャナー表示中（MainActivity が STOPPED）に
    // emit されたイベントがコレクター停止中に消えるため。
    private val _eventChannel = Channel<ViewEvent>(Channel.BUFFERED)
    val eventFlow = _eventChannel.receiveAsFlow()

    // onResume での自動スキャン許可フラグ。
    // 初回起動時と、外部アプリ起動から戻ってきたときだけ true。
    // スキャン成功直後の onResume（GMS スキャナーが閉じた瞬間）はここが false のため
    // 自動再スキャンが走らず、ブラウザ起動とスキャナー再表示が競合しない。
    private var autoScanOnResume = true

    sealed class ViewEvent {
        data class StartActivity(val intent: Intent) : ViewEvent()
        data class ShowToast(@StringRes val messageRes: Int, val formatArg: String? = null) : ViewEvent()
    }

    init {
        viewModelScope.launch {
            val history = withContext(ioDispatcher) { scanHistoryRepository.getHistory() }
            _uiState.update { it.copy(history = history) }
        }
    }

    /** onResume で自動スキャンすべきなら true を返し、フラグを消費する。 */
    fun consumeAutoScanRequest(): Boolean {
        val shouldScan = autoScanOnResume
        autoScanOnResume = false
        return shouldScan
    }

    /** StartActivity が成功したら、外部アプリから戻った次の onResume で再スキャンする。 */
    fun onLaunchSucceeded() {
        autoScanOnResume = true
    }

    fun onScanStarted() {
        _uiState.update { it.copy(isScanning = true) }
    }

    /** 共有画像のデコード開始。onResume のカメラ自動スキャンを抑止する。 */
    fun onImageScanStarted() {
        autoScanOnResume = false
        _uiState.update { it.copy(isScanning = true) }
    }

    fun onScanSuccess(qrCodeValue: String?) {
        _uiState.update { it.copy(isScanning = false) }
        if (qrCodeValue != null) {
            handleScannedValue(qrCodeValue)
        } else {
            viewModelScope.launch {
                _eventChannel.send(ViewEvent.ShowToast(R.string.scan_no_data))
            }
        }
    }

    // キャンセル時は待機画面に戻るだけ。即時再スキャンするとユーザーが
    // 戻る操作でアプリを終了できなくなる。
    fun onScanCanceled() {
        _uiState.update { it.copy(isScanning = false) }
    }

    fun onScanFailed(exception: Exception) {
        Log.e(TAG, "Scan failed", exception)
        _uiState.update { it.copy(isScanning = false) }
        viewModelScope.launch {
            _eventChannel.send(ViewEvent.ShowToast(R.string.scan_failed_simple))
        }
    }

    /** 共有画像から QR コードを読み取れなかった。 */
    fun onImageScanFailed(exception: Exception? = null) {
        exception?.let { Log.e(TAG, "Image scan failed", it) }
        _uiState.update { it.copy(isScanning = false) }
        viewModelScope.launch {
            _eventChannel.send(ViewEvent.ShowToast(R.string.no_qr_found_in_image))
        }
    }

    fun onHistoryItemSelected(value: String) {
        handleScannedValue(value)
    }

    fun onModuleInstallFailed(message: String?) {
        _uiState.update { it.copy(moduleError = message ?: "unknown") }
    }

    fun onModuleInstallRetry() {
        _uiState.update { it.copy(moduleError = null) }
    }

    private fun handleScannedValue(value: String) {
        _uiState.update { it.copy(lastScannedValue = value) }
        recordHistory(value)
        processQrCode(value)
    }

    private fun recordHistory(value: String) {
        viewModelScope.launch {
            val updated = withContext(ioDispatcher) { scanHistoryRepository.addEntry(value) }
            _uiState.update { it.copy(history = updated) }
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
        private const val TAG = "MainViewModel"

        // Manual Factory pattern used instead of Hilt, as this app has no other DI requirements.
        fun Factory(application: Application): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                    val appRepository = DefaultAppRepository(application.applicationContext)
                    val handleQrCodeUseCase = HandleQrCodeUseCase(appRepository)
                    val scanHistoryRepository = SharedPrefsScanHistoryRepository(application.applicationContext)
                    return MainViewModel(handleQrCodeUseCase, scanHistoryRepository) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class")
            }
        }
    }
}
