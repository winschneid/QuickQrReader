package com.ks.app.quickqrreader.ui

import android.content.Intent
import android.net.Uri
import com.ks.app.quickqrreader.R
import com.ks.app.quickqrreader.data.ScanHistoryRepository
import com.ks.app.quickqrreader.domain.HandleQrCodeUseCase
import com.ks.app.quickqrreader.domain.QrCodeProcessingResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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

    @Test
    fun `onScanStarted should update uiState to isScanning true`() = testScope.runTest {
        viewModel.onScanStarted()
        assertTrue(viewModel.uiState.value.isScanning)
    }

    @Test
    fun `onScanSuccess with QR code should process QR and emit StartActivity event on Success result`() = testScope.runTest {
        // Arrange
        `when`(mockHandleQrCodeUseCase.invoke(testQrCode)).thenReturn(QrCodeProcessingResult.Success(testIntent))

        val events = mutableListOf<MainViewModel.ViewEvent>()
        val job = launch { viewModel.eventFlow.collect { events.add(it) } }

        // Act
        viewModel.onScanSuccess(testQrCode)
        advanceUntilIdle() // Allow coroutines to complete

        // Assert
        assertFalse(viewModel.uiState.value.isScanning)
        verify(mockHandleQrCodeUseCase).invoke(testQrCode)
        assertEquals(1, events.size)
        assertTrue(events[0] is MainViewModel.ViewEvent.StartActivity)
        assertEquals(testIntent, (events[0] as MainViewModel.ViewEvent.StartActivity).intent)

        job.cancel()
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

        val events = mutableListOf<MainViewModel.ViewEvent>()
        val job = launch { viewModel.eventFlow.collect { events.add(it) } }

        // Act
        viewModel.onScanSuccess(testQrCode)
        advanceUntilIdle()

        // Assert
        assertFalse(viewModel.uiState.value.isScanning)
        verify(mockHandleQrCodeUseCase).invoke(testQrCode)
        assertEquals(1, events.size)
        assertTrue(events[0] is MainViewModel.ViewEvent.ShowToast)
        val toast = events[0] as MainViewModel.ViewEvent.ShowToast
        assertEquals(R.string.cannot_open, toast.messageRes)
        assertEquals(testQrCode, toast.formatArg)

        job.cancel()
    }

    @Test
    fun `onScanSuccess with null QR code should emit ShowToast event for no data`() = testScope.runTest {
        val events = mutableListOf<MainViewModel.ViewEvent>()
        val job = launch { viewModel.eventFlow.collect { events.add(it) } }

        viewModel.onScanSuccess(null)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isScanning)
        assertEquals(1, events.size)
        assertTrue(events[0] is MainViewModel.ViewEvent.ShowToast)
        val toast = events[0] as MainViewModel.ViewEvent.ShowToast
        assertEquals(R.string.scan_no_data, toast.messageRes)
        assertNull(toast.formatArg)
        job.cancel()
    }

    @Test
    fun `onScanCanceled should return to idle without emitting events`() = testScope.runTest {
        val events = mutableListOf<MainViewModel.ViewEvent>()
        val job = launch { viewModel.eventFlow.collect { events.add(it) } }

        viewModel.onScanStarted()
        viewModel.onScanCanceled()
        advanceUntilIdle()

        // キャンセルは待機画面に戻るだけ。トーストや再スキャンはしない。
        assertFalse(viewModel.uiState.value.isScanning)
        assertTrue(events.isEmpty())
        job.cancel()
    }

    @Test
    fun `onScanFailed should update uiState and emit generic failure toast`() = testScope.runTest {
        val testException = RuntimeException("Device unavailable")

        val events = mutableListOf<MainViewModel.ViewEvent>()
        val job = launch { viewModel.eventFlow.collect { events.add(it) } }

        viewModel.onScanFailed(testException)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isScanning)
        assertEquals(1, events.size)
        assertTrue(events[0] is MainViewModel.ViewEvent.ShowToast)
        val toast = events[0] as MainViewModel.ViewEvent.ShowToast
        // 例外メッセージはユーザーに出さずログに送るため定型文のみ
        assertEquals(R.string.scan_failed_simple, toast.messageRes)
        assertNull(toast.formatArg)
        job.cancel()
    }

    @Test
    fun `consumeAutoScanRequest should be true only on first call after launch`() = testScope.runTest {
        assertTrue(viewModel.consumeAutoScanRequest())
        assertFalse(viewModel.consumeAutoScanRequest())
    }

    @Test
    fun `auto scan should be re-armed after external launch succeeded`() = testScope.runTest {
        `when`(mockHandleQrCodeUseCase.invoke(testQrCode)).thenReturn(QrCodeProcessingResult.Success(testIntent))
        viewModel.consumeAutoScanRequest() // 初回起動分を消費
        viewModel.onScanStarted()
        viewModel.onScanSuccess(testQrCode) // スキャナーが閉じた直後の onResume を想定

        // StartActivity イベント未処理の間は自動再スキャンしない（ブラウザ競合バグの回帰防止）
        assertFalse(viewModel.consumeAutoScanRequest())

        viewModel.onLaunchSucceeded() // Activity が startActivity に成功

        // 外部アプリから戻ってきた onResume では再スキャンする
        assertTrue(viewModel.consumeAutoScanRequest())
    }

    @Test
    fun `onImageScanStarted should mark scanning and suppress auto scan`() = testScope.runTest {
        viewModel.onImageScanStarted()

        assertTrue(viewModel.uiState.value.isScanning)
        assertFalse(viewModel.consumeAutoScanRequest())
    }

    @Test
    fun `onImageScanFailed should emit no-qr-found toast`() = testScope.runTest {
        val events = mutableListOf<MainViewModel.ViewEvent>()
        val job = launch { viewModel.eventFlow.collect { events.add(it) } }

        viewModel.onImageScanStarted()
        viewModel.onImageScanFailed()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isScanning)
        assertEquals(1, events.size)
        val toast = events[0] as MainViewModel.ViewEvent.ShowToast
        assertEquals(R.string.no_qr_found_in_image, toast.messageRes)
        job.cancel()
    }

    @Test
    fun `onHistoryItemSelected should reprocess the value and move it to history front`() = testScope.runTest {
        `when`(mockHandleQrCodeUseCase.invoke(testQrCode)).thenReturn(QrCodeProcessingResult.Success(testIntent))
        historyRepository.addEntry(testQrCode)
        historyRepository.addEntry("newer_entry")

        val events = mutableListOf<MainViewModel.ViewEvent>()
        val job = launch { viewModel.eventFlow.collect { events.add(it) } }

        viewModel.onHistoryItemSelected(testQrCode)
        advanceUntilIdle()

        assertEquals(1, events.size)
        assertTrue(events[0] is MainViewModel.ViewEvent.StartActivity)
        assertEquals(listOf(testQrCode, "newer_entry"), viewModel.uiState.value.history)
        job.cancel()
    }

    @Test
    fun `module install failure and retry should toggle moduleError state`() = testScope.runTest {
        viewModel.onModuleInstallFailed("network error")
        assertEquals("network error", viewModel.uiState.value.moduleError)

        viewModel.onModuleInstallRetry()
        assertNull(viewModel.uiState.value.moduleError)
    }
}
