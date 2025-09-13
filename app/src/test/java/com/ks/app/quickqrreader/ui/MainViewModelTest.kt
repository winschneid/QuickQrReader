package com.ks.app.quickqrreader.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import com.ks.app.quickqrreader.R
import com.ks.app.quickqrreader.domain.HandleQrCodeUseCase
import com.ks.app.quickqrreader.domain.QrCodeProcessingResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first // Keep if you use it, otherwise remove
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify // Ensure verify is imported
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.eq

@ExperimentalCoroutinesApi
class MainViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Mock
    private lateinit var mockApplication: Application

    @Mock
    private lateinit var mockHandleQrCodeUseCase: HandleQrCodeUseCase

    private lateinit var viewModel: MainViewModel

    // Test data
    private val testQrCode = "test_qr_code_value"
    private val testIntent = Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com"))
    // private val genericErrorMessage = "An error occurred" // Not used with current getString mocks
    private val cannotOpenMessageFormat = "Cannot open: %s"
    private val scanFailedTestMessageFormat = "Scan failed: %s"
    private val scanCanceledMessage = "Scan canceled by user"
    private val scanNoDataMessage = "Scan successful, no data"

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        Dispatchers.setMain(testDispatcher)
        
        // Mock Application context getString calls
        // Ensure applicationContext returns the mockApplication itself for chaining
        `when`(mockApplication.applicationContext).thenReturn(mockApplication)
        `when`(mockApplication.getString(R.string.cannot_open, testQrCode)).thenReturn(String.format(cannotOpenMessageFormat, testQrCode))
        `when`(mockApplication.getString(eq(R.string.scan_failed_message), any<String>())).thenAnswer { invocation ->
            String.format(scanFailedTestMessageFormat, invocation.arguments[1])
        }
        `when`(mockApplication.getString(R.string.scan_canceled)).thenReturn(scanCanceledMessage)
        `when`(mockApplication.getString(R.string.scan_no_data)).thenReturn(scanNoDataMessage)

        // Instantiate ViewModel with mocked dependencies
        viewModel = MainViewModel(mockApplication, mockHandleQrCodeUseCase)
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
    fun `onScanSuccess with QR code should emit ShowToast event on Error result from UseCase`() = testScope.runTest {
        // Arrange
        `when`(mockHandleQrCodeUseCase.invoke(testQrCode)).thenReturn(QrCodeProcessingResult.Error(testQrCode))
        val expectedMessage = String.format(cannotOpenMessageFormat, testQrCode)

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
        assertEquals(expectedMessage, (events[0] as MainViewModel.ViewEvent.ShowToast).message)

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
        assertEquals(scanNoDataMessage, (events[0] as MainViewModel.ViewEvent.ShowToast).message)
        job.cancel()
    }

    @Test
    fun `onScanCanceled should update uiState and emit ShowToast event`() = testScope.runTest {
        val events = mutableListOf<MainViewModel.ViewEvent>()
        val job = launch { viewModel.eventFlow.collect { events.add(it) } }

        viewModel.onScanCanceled()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isScanning)
        assertEquals(1, events.size)
        assertTrue(events[0] is MainViewModel.ViewEvent.ShowToast)
        assertEquals(scanCanceledMessage, (events[0] as MainViewModel.ViewEvent.ShowToast).message)
        job.cancel()
    }

    @Test
    fun `onScanFailed should update uiState and emit ShowToast event`() = testScope.runTest {
        val exceptionMessage = "Device unavailable"
        val testException = RuntimeException(exceptionMessage)
        val expectedToastMessage = String.format(scanFailedTestMessageFormat, exceptionMessage)
        
        val events = mutableListOf<MainViewModel.ViewEvent>()
        val job = launch { viewModel.eventFlow.collect { events.add(it) } }

        viewModel.onScanFailed(testException)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isScanning)
        assertEquals(1, events.size)
        assertTrue(events[0] is MainViewModel.ViewEvent.ShowToast)
        assertEquals(expectedToastMessage, (events[0] as MainViewModel.ViewEvent.ShowToast).message)
        job.cancel()
    }
}
