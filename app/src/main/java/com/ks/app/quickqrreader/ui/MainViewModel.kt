package com.ks.app.quickqrreader.ui

import android.app.Application
import android.content.Intent
import android.os.SystemClock
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

data class MainUiState(
    val isScanning: Boolean = false,
    val isScanningSerial: Boolean = false,
    val lastScannedValue: String? = null,
    val history: List<String> = emptyList(),
    val moduleError: String? = null,
    /** Activity がまだ処理していないイベント。処理されるまで状態として残り続ける。 */
    val pendingEvents: List<PendingViewEvent> = emptyList()
)

/** 未処理イベント。`id` は Activity が「どれを処理したか」を伝えるためだけに使う。 */
data class PendingViewEvent(
    val id: Long,
    val event: MainViewModel.ViewEvent
)

class MainViewModel(
    private val handleQrCodeUseCase: HandleQrCodeUseCase,
    private val scanHistoryRepository: ScanHistoryRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    // スキャナーを開いていた時間を測るためだけの時刻源。テストで差し替える。
    private val elapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() }
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    // イベントは Flow ではなく状態として持ち、Activity が処理し終えたと伝えてきたときだけ消す。
    //
    // 以前は Channel + receiveAsFlow() を flowWithLifecycle(STARTED) で購読していた。
    // GMS スキャナー表示中（MainActivity が STOPPED）に emit されたイベントは
    // Channel がバッファするので届くが、receiveAsFlow() は「チャネルから受け取ったが
    // まだ emit していない要素」をコレクター解除時に取りこぼす。
    // スキャナーが閉じる前後でライフサイクルが STARTED を跨ぐため、
    // 「QR を読んだのに何も起きない」が起こり得た。
    //
    // 状態として持てば、コレクターが何度解除されても未処理イベントは残り、
    // 次に STARTED になったときに再配信される。
    private val eventIdGenerator = AtomicLong(0L)

    // onResume での自動スキャン許可フラグ。ViewModel の生存期間で一度だけ true になる。
    //
    // 自動でスキャナーを開くのはコールドスタート時のみ。以降の onResume
    // （外部アプリから戻った / ホームから戻った / 画面回転）では開かない。
    // ・スキャン成功直後の onResume（GMS スキャナーが閉じた瞬間）に再スキャンが走ると
    //   起動したブラウザの上にスキャナーが被さる。
    // ・外部アプリから戻るたびに再スキャンすると、読み取り結果や履歴を確認する間もなく
    //   カメラが開き、ユーザーが待機画面に留まれない。
    // ・共有画像から起動した場合はカメラを一度も使わないため、戻ってきて開くのは不自然。
    // 次のスキャンは待機画面のボタンからユーザーが明示的に開始する。
    private var autoScanOnResume = true

    // カメラのスキャナーを開いた時刻。キャンセル時に「粘ったが読めなかった」のか
    // 「開いてすぐ閉じた」のかを見分けるためだけに持つ。
    private var cameraScanStartedAtMs: Long? = null

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

    /**
     * 読み取り結果から外部アプリ（ブラウザ等）の起動に成功した。
     *
     * ここで自動スキャンを再武装しないことが重要。戻ってきた onResume でカメラが開くと、
     * ユーザーは読み取り結果も履歴も確認できないまま次のスキャンに放り込まれる。
     * 通常フローでは onResume が先に消費済みなので実質的な no-op だが、
     * 「起動後に自動スキャンへ戻らない」という契約を呼び出し側と共有するために明示する。
     */
    fun onExternalAppLaunched() {
        autoScanOnResume = false
    }

    /**
     * Activity が [MainUiState.pendingEvents] のイベントを処理し終えた。
     *
     * 呼び出し側は「取り出してから中断せずに処理する」こと。中断点を挟まずに
     * このメソッドで消してから処理すれば、コレクターが解除されてもイベントは
     * 失われず、かつ二重処理もされない。
     */
    fun onEventsHandled(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val handled = ids.toSet()
        _uiState.update { state ->
            state.copy(pendingEvents = state.pendingEvents.filterNot { it.id in handled })
        }
    }

    fun onScanStarted() {
        cameraScanStartedAtMs = elapsedRealtimeMs()
        _uiState.update { it.copy(isScanning = true) }
    }

    /** 共有画像のデコード開始。onResume のカメラ自動スキャンを抑止する。 */
    fun onImageScanStarted() {
        autoScanOnResume = false
        _uiState.update { it.copy(isScanning = true) }
    }

    fun onScanSuccess(qrCodeValue: String?) {
        cameraScanStartedAtMs = null
        _uiState.update { it.copy(isScanning = false) }
        if (qrCodeValue != null) {
            handleScannedValue(qrCodeValue)
        } else {
            emitEvent(ViewEvent.ShowToast(R.string.scan_no_data))
        }
    }

    // キャンセル時は待機画面に戻るだけ。即時再スキャンするとユーザーが
    // 戻る操作でアプリを終了できなくなる。
    fun onScanCanceled() {
        val openedAtMs = cameraScanStartedAtMs
        cameraScanStartedAtMs = null
        _uiState.update { it.copy(isScanning = false) }

        // GmsBarcodeScanner は読み取れたときしか完了しない。読めない QR に当てていると
        // スキャナーは回り続け、ユーザーは戻る操作で諦めるしかない。失敗イベントが
        // 存在しないので、「長く開いた末のキャンセル」を読めなかった合図として扱う。
        //
        // 日本語テキストを含む QR（Shift_JIS バイトモード / Kanji モード）はカメラ経路では
        // 読めないが、共有画像経路なら読める。そこへ誘導する。
        // 開いてすぐ閉じた場合は単に気が変わっただけなので黙って戻る。
        if (openedAtMs != null && elapsedRealtimeMs() - openedAtMs >= STRUGGLED_SCAN_THRESHOLD_MS) {
            emitEvent(ViewEvent.ShowToast(R.string.hint_share_image_instead))
        }
    }

    fun onScanFailed(exception: Exception) {
        Log.e(TAG, "Scan failed", exception)
        cameraScanStartedAtMs = null
        _uiState.update { it.copy(isScanning = false) }
        emitEvent(ViewEvent.ShowToast(R.string.scan_failed_simple))
    }

    /** 共有画像から QR コードを読み取れなかった。 */
    fun onImageScanFailed(exception: Exception? = null) {
        exception?.let { Log.e(TAG, "Image scan failed", it) }
        _uiState.update { it.copy(isScanning = false) }
        emitEvent(ViewEvent.ShowToast(R.string.no_qr_found_in_image))
    }

    fun onHistoryItemSelected(value: String) {
        handleScannedValue(value)
    }

    // カメラでのライブ文字列（応募シリアル）スキャンはQRスキャンと完全に独立したオプション機能。
    // isScanning や pendingEvents には一切触れない。
    fun onSerialScanStarted() {
        _uiState.update { it.copy(isScanningSerial = true) }
    }

    fun onSerialScanFinished() {
        _uiState.update { it.copy(isScanningSerial = false) }
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
        when (val result = handleQrCodeUseCase(qrCode)) {
            is QrCodeProcessingResult.Success ->
                emitEvent(ViewEvent.StartActivity(result.intent))
            is QrCodeProcessingResult.Error ->
                emitEvent(ViewEvent.ShowToast(R.string.cannot_open, result.originalQrCode))
        }
    }

    private fun emitEvent(event: ViewEvent) {
        _uiState.update {
            it.copy(pendingEvents = it.pendingEvents + PendingViewEvent(eventIdGenerator.getAndIncrement(), event))
        }
    }

    companion object {
        private const val TAG = "MainViewModel"

        // これより長くスキャナーを開いた末にキャンセルしたら、読めなくて諦めたと見なす。
        // 短すぎると「気が変わっただけ」の人にも案内が出るため、余裕を持たせている。
        private const val STRUGGLED_SCAN_THRESHOLD_MS = 8_000L

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
