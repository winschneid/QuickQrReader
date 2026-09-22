package com.ks.app.quickqrreader.ui

import android.content.Intent
import android.net.Uri
import com.ks.app.quickqrreader.R
import com.ks.app.quickqrreader.data.ScanHistoryRepository
import com.ks.app.quickqrreader.domain.HandleQrCodeUseCase
import com.ks.app.quickqrreader.domain.QrCodeProcessingResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@ExperimentalCoroutinesApi
class MainViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Mock
    private lateinit var mockHandleQrCodeUseCase: HandleQrCodeUseCase

    private lateinit var historyRepository: FakeScanHistoryRepository
    private lateinit var viewModel: MainViewModel

    // Test data
    private val testQrCode = "test_qr_code_value"
    private val testIntent = Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com"))

    // SharedPreferences 実装の代わりに使うインメモリ履歴
    private class FakeScanHistoryRepository : ScanHistoryRepository {
        private val entries = mutableListOf<String>()
        override fun getHistory(): List<String> = entries.toList()
        override fun addEntry(value: String): List<String> {
            entries.remove(value)
            entries.add(0, value)
            return entries.toList()
        }
    }

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        Dispatchers.setMain(testDispatcher)

        historyRepository = FakeScanHistoryRepository()
        viewModel = MainViewModel(mockHandleQrCodeUseCase, historyRepository, testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // 未処理イベントは Flow ではなく状態として残る。Activity が onEventsHandled() を
    // 呼ぶまで消えないので、テストは「購読する」のではなく「状態を覗く」。
    private val pendingEvents: List<MainViewModel.ViewEvent>
        get() = viewModel.uiState.value.pendingEvents.map { it.event }

    private val pendingEventIds: List<Long>
        get() = viewModel.uiState.value.pendingEvents.map { it.id }

    @Test
    fun `onScanStarted should update uiState to isScanning true`() = testScope.runTest {
        viewModel.onScanStarted()
        assertTrue(viewModel.uiState.value.isScanning)
    }

    @Test
    fun `onScanSuccess with QR code should process QR and emit StartActivity event on Success result`() = testScope.runTest {
        // Arrange
        `when`(mockHandleQrCodeUseCase.invoke(testQrCode)).thenReturn(QrCodeProcessingResult.Success(testIntent))

        // Act
        viewModel.onScanSuccess(testQrCode)
        advanceUntilIdle() // Allow coroutines to complete

        // Assert
        assertFalse(viewModel.uiState.value.isScanning)
        verify(mockHandleQrCodeUseCase).invoke(testQrCode)
        assertEquals(1, pendingEvents.size)
        assertTrue(pendingEvents[0] is MainViewModel.ViewEvent.StartActivity)
        assertEquals(testIntent, (pendingEvents[0] as MainViewModel.ViewEvent.StartActivity).intent)
    }

    @Test
    fun `onScanSuccess should record the value as last result and history`() = testScope.runTest {
        `when`(mockHandleQrCodeUseCase.invoke(testQrCode)).thenReturn(QrCodeProcessingResult.Success(testIntent))

        viewModel.onScanSuccess(testQrCode)
        advanceUntilIdle()

        assertEquals(testQrCode, viewModel.uiState.value.lastScannedValue)
        assertEquals(listOf(testQrCode), viewModel.uiState.value.history)
    }

    @Test
    fun `init should load persisted history into uiState`() = testScope.runTest {
        historyRepository.addEntry("persisted_value")
        val newViewModel = MainViewModel(mockHandleQrCodeUseCase, historyRepository, testDispatcher)
        advanceUntilIdle()

        assertEquals(listOf("persisted_value"), newViewModel.uiState.value.history)
    }

    @Test
    fun `onScanSuccess with QR code should emit ShowToast event on Error result from UseCase`() = testScope.runTest {
        // Arrange
        `when`(mockHandleQrCodeUseCase.invoke(testQrCode)).thenReturn(QrCodeProcessingResult.Error(testQrCode))

        // Act
        viewModel.onScanSuccess(testQrCode)
        advanceUntilIdle()

        // Assert
        assertFalse(viewModel.uiState.value.isScanning)
        verify(mockHandleQrCodeUseCase).invoke(testQrCode)
        assertEquals(1, pendingEvents.size)
        assertTrue(pendingEvents[0] is MainViewModel.ViewEvent.ShowToast)
        val toast = pendingEvents[0] as MainViewModel.ViewEvent.ShowToast
        assertEquals(R.string.cannot_open, toast.messageRes)
        assertEquals(testQrCode, toast.formatArg)
    }

    @Test
    fun `onScanSuccess with null QR code should emit ShowToast event for no data`() = testScope.runTest {

        viewModel.onScanSuccess(null)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isScanning)
        assertEquals(1, pendingEvents.size)
        assertTrue(pendingEvents[0] is MainViewModel.ViewEvent.ShowToast)
        val toast = pendingEvents[0] as MainViewModel.ViewEvent.ShowToast
        assertEquals(R.string.scan_no_data, toast.messageRes)
        assertNull(toast.formatArg)
    }

    @Test
    fun `onScanCanceled should return to idle without emitting events`() = testScope.runTest {

        viewModel.onScanStarted()
        viewModel.onScanCanceled()
        advanceUntilIdle()

        // キャンセルは待機画面に戻るだけ。トーストや再スキャンはしない。
        assertFalse(viewModel.uiState.value.isScanning)
        assertTrue(pendingEvents.isEmpty())
    }

    @Test
    fun `onScanFailed should update uiState and emit generic failure toast`() = testScope.runTest {
        val testException = RuntimeException("Device unavailable")

        viewModel.onScanFailed(testException)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isScanning)
        assertEquals(1, pendingEvents.size)
        assertTrue(pendingEvents[0] is MainViewModel.ViewEvent.ShowToast)
        val toast = pendingEvents[0] as MainViewModel.ViewEvent.ShowToast
        // 例外メッセージはユーザーに出さずログに送るため定型文のみ
        assertEquals(R.string.scan_failed_simple, toast.messageRes)
        assertNull(toast.formatArg)
    }

    @Test
    fun `consumeAutoScanRequest should be true only on first call after launch`() = testScope.runTest {
        assertTrue(viewModel.consumeAutoScanRequest())
        assertFalse(viewModel.consumeAutoScanRequest())
    }

    @Test
    fun `auto scan should stay off for every resume after a handled scan`() = testScope.runTest {
        `when`(mockHandleQrCodeUseCase.invoke(testQrCode)).thenReturn(QrCodeProcessingResult.Success(testIntent))
        viewModel.consumeAutoScanRequest() // コールドスタート分を消費
        viewModel.onScanStarted()
        viewModel.onScanSuccess(testQrCode) // スキャナーが閉じた直後の onResume を想定
        advanceUntilIdle()

        // スキャナーが閉じた瞬間の onResume（ブラウザ競合バグの回帰防止）
        assertFalse(viewModel.consumeAutoScanRequest())

        viewModel.onExternalAppLaunched() // Activity が startActivity に成功

        // 外部アプリから戻ってきた onResume でも再スキャンしない。
        // 待機画面に留まり、次のスキャンはユーザーがボタンで開始する。
        assertFalse(viewModel.consumeAutoScanRequest())
        assertFalse(viewModel.consumeAutoScanRequest())
    }

    @Test
    fun `auto scan should stay off after the scanner was canceled`() = testScope.runTest {
        viewModel.consumeAutoScanRequest() // コールドスタート分を消費
        viewModel.onScanStarted()
        viewModel.onScanCanceled()

        // 戻る操作でスキャナーを閉じた後にまたスキャナーが開くと、アプリから抜けられない
        assertFalse(viewModel.uiState.value.isScanning)
        assertFalse(viewModel.consumeAutoScanRequest())
    }

    @Test
    fun `auto scan should stay off after a shared image was handled`() = testScope.runTest {
        `when`(mockHandleQrCodeUseCase.invoke(testQrCode)).thenReturn(QrCodeProcessingResult.Success(testIntent))
        viewModel.onImageScanStarted() // 共有画像から起動（カメラは使わない）
        viewModel.onScanSuccess(testQrCode)
        advanceUntilIdle()
        viewModel.onExternalAppLaunched()

        // 共有画像経由で開いたアプリから戻ってきてもカメラを起動しない
        assertFalse(viewModel.consumeAutoScanRequest())
    }

    @Test
    fun `onImageScanStarted should mark scanning and suppress auto scan`() = testScope.runTest {
        viewModel.onImageScanStarted()

        assertTrue(viewModel.uiState.value.isScanning)
        assertFalse(viewModel.consumeAutoScanRequest())
    }

    @Test
    fun `onImageScanFailed should emit no-qr-found toast`() = testScope.runTest {

        viewModel.onImageScanStarted()
        viewModel.onImageScanFailed()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isScanning)
        assertEquals(1, pendingEvents.size)
        val toast = pendingEvents[0] as MainViewModel.ViewEvent.ShowToast
        assertEquals(R.string.no_qr_found_in_image, toast.messageRes)
    }

    @Test
    fun `onHistoryItemSelected should reprocess the value and move it to history front`() = testScope.runTest {
        `when`(mockHandleQrCodeUseCase.invoke(testQrCode)).thenReturn(QrCodeProcessingResult.Success(testIntent))
        historyRepository.addEntry(testQrCode)
        historyRepository.addEntry("newer_entry")

        viewModel.onHistoryItemSelected(testQrCode)
        advanceUntilIdle()

        assertEquals(1, pendingEvents.size)
        assertTrue(pendingEvents[0] is MainViewModel.ViewEvent.StartActivity)
        assertEquals(listOf(testQrCode, "newer_entry"), viewModel.uiState.value.history)
    }

    // --- イベント配信の契約: Activity が処理したと伝えるまで消えない ---

    @Test
    fun `pending event should survive until the activity reports it handled`() = testScope.runTest {
        `when`(mockHandleQrCodeUseCase.invoke(testQrCode)).thenReturn(QrCodeProcessingResult.Success(testIntent))

        viewModel.onScanSuccess(testQrCode)
        advanceUntilIdle()

        // Activity が STOPPED で受け取れない間、イベントは状態に残り続ける。
        // 何度読んでも消えない（購読の解除でイベントが失われないことの担保）。
        assertEquals(1, pendingEvents.size)
        assertEquals(1, pendingEvents.size)
        assertEquals(1, pendingEvents.size)

        viewModel.onEventsHandled(pendingEventIds)

        assertTrue(pendingEvents.isEmpty())
    }

    @Test
    fun `events should queue in order while unhandled`() = testScope.runTest {
        viewModel.onScanSuccess(null) // scan_no_data
        viewModel.onScanFailed(RuntimeException("boom")) // scan_failed_simple
        advanceUntilIdle()

        assertEquals(2, pendingEvents.size)
        assertEquals(
            listOf(R.string.scan_no_data, R.string.scan_failed_simple),
            pendingEvents.map { (it as MainViewModel.ViewEvent.ShowToast).messageRes }
        )
    }

    @Test
    fun `onEventsHandled should only remove the reported events`() = testScope.runTest {
        viewModel.onScanSuccess(null)
        viewModel.onScanFailed(RuntimeException("boom"))
        advanceUntilIdle()
        val firstId = pendingEventIds.first()

        viewModel.onEventsHandled(listOf(firstId))

        // 2件目は未処理のまま残る（まとめて消して取りこぼす、が起きないこと）
        assertEquals(1, pendingEvents.size)
        assertEquals(
            R.string.scan_failed_simple,
            (pendingEvents[0] as MainViewModel.ViewEvent.ShowToast).messageRes
        )
    }

    @Test
    fun `onEventsHandled should ignore ids that are not pending`() = testScope.runTest {
        viewModel.onScanSuccess(null)
        advanceUntilIdle()

        // 再配信されたイベントを二重に報告しても、未処理イベントを巻き込んで消さない
        viewModel.onEventsHandled(listOf(9999L))
        assertEquals(1, pendingEvents.size)

        viewModel.onEventsHandled(emptyList())
        assertEquals(1, pendingEvents.size)
    }

    @Test
    fun `module install failure and retry should toggle moduleError state`() = testScope.runTest {
        viewModel.onModuleInstallFailed("network error")
        assertEquals("network error", viewModel.uiState.value.moduleError)

        viewModel.onModuleInstallRetry()
        assertNull(viewModel.uiState.value.moduleError)
    }
}
