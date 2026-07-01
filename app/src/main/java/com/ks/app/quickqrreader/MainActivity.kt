package com.ks.app.quickqrreader

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview as ComposePreview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.ks.app.quickqrreader.domain.SerialExtractor
import com.ks.app.quickqrreader.ui.MainUiState
import com.ks.app.quickqrreader.ui.MainViewModel
import com.ks.app.quickqrreader.ui.theme.QuickQrReaderTheme
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    private lateinit var scanner: GmsBarcodeScanner
    private lateinit var cameraPermissionLauncher: ActivityResultLauncher<String>
    private val viewModel: MainViewModel by viewModels {
        MainViewModel.Factory(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        scanner = GmsBarcodeScanning.getClient(this, options)

        cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                viewModel.onSerialScanStarted()
            } else {
                Toast.makeText(this, getString(R.string.camera_permission_denied), Toast.LENGTH_SHORT).show()
            }
        }

        installModuleIfNeeded()

        viewModel.eventFlow
            .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
            .onEach { event -> handleViewEvent(event) }
            .launchIn(lifecycleScope)

        setContent {
            QuickQrReaderTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    QrScannerScreen(
                        modifier = Modifier.padding(innerPadding),
                        uiState = uiState,
                        onStartScan = ::startScanning,
                        onHistoryItemClick = viewModel::onHistoryItemSelected,
                        onCopy = ::copyToClipboard,
                        onRetryModuleInstall = {
                            viewModel.onModuleInstallRetry()
                            installModuleIfNeeded()
                            startScanning()
                        },
                        onStartSerialScan = ::startSerialScanning,
                        onSerialConfirmed = ::onSerialConfirmedFromCamera,
                        onCancelSerialScan = viewModel::onSerialScanFinished
                    )
                }
            }
        }

        if (savedInstanceState == null) {
            handleShareIntent(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        // 自動スキャンは初回起動時と外部アプリから戻ったときのみ。
        // 無条件に開始すると、読み取り成功直後の onResume（スキャナーが閉じた瞬間）に
        // 新しいスキャンが始まり、直後に起動したブラウザの上に GMS スキャナーが
        // 被さって「ブラウザが一瞬で閉じてアプリに戻る」現象が起きる。
        if (viewModel.consumeAutoScanRequest()) {
            startScanning()
        }
    }

    private fun handleViewEvent(event: MainViewModel.ViewEvent) {
        when (event) {
            is MainViewModel.ViewEvent.StartActivity -> {
                try {
                    startActivity(event.intent)
                    viewModel.onLaunchSucceeded()
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this, getString(R.string.no_app_to_open), Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, getString(R.string.scan_failed, e.message), Toast.LENGTH_SHORT).show()
                }
            }
            is MainViewModel.ViewEvent.ShowToast -> {
                val message = if (event.formatArg != null) {
                    getString(event.messageRes, event.formatArg)
                } else {
                    getString(event.messageRes)
                }
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun installModuleIfNeeded() {
        val moduleInstall = ModuleInstall.getClient(this)
        val moduleInstallRequest = ModuleInstallRequest.newBuilder()
            .addApi(GmsBarcodeScanning.getClient(this))
            .build()

        moduleInstall.installModules(moduleInstallRequest)
            .addOnSuccessListener {
                if (!it.areModulesAlreadyInstalled()) {
                    Toast.makeText(this, getString(R.string.module_install_success), Toast.LENGTH_LONG).show()
                }
            }
            .addOnFailureListener { exception ->
                viewModel.onModuleInstallFailed(exception.message)
            }
    }

    private fun startScanning() {
        if (viewModel.uiState.value.isScanning) return

        viewModel.onScanStarted()
        scanner.startScan()
            .addOnSuccessListener { barcode ->
                viewModel.onScanSuccess(barcode.rawValue)
            }
            .addOnCanceledListener {
                viewModel.onScanCanceled()
            }
            .addOnFailureListener { exception ->
                viewModel.onScanFailed(exception)
            }
    }

    // 他アプリから共有された画像（スクリーンショット等）の QR コードを読み取る
    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type?.startsWith("image/") != true) return
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        } ?: return

        viewModel.onImageScanStarted()
        val inputImage = try {
            InputImage.fromFilePath(this, uri)
        } catch (e: Exception) {
            viewModel.onImageScanFailed(e)
            return
        }
        scanBarcodeFromImage(inputImage)
        // オプション機能。失敗してもアプリ本来のQRコード読み取りには一切影響させない。
        extractSerialFromImage(inputImage)
    }

    private fun scanBarcodeFromImage(inputImage: InputImage) {
        val imageScanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
        imageScanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                val value = barcodes.firstOrNull { !it.rawValue.isNullOrEmpty() }?.rawValue
                if (value != null) {
                    viewModel.onScanSuccess(value)
                } else {
                    viewModel.onImageScanFailed()
                }
            }
            .addOnFailureListener { exception ->
                viewModel.onImageScanFailed(exception)
            }
            .addOnCompleteListener { imageScanner.close() }
    }

    // 共有画像の「応募シリアル」欄をOCRで読み取りクリップボードにコピーするオプション機能。
    // isScanning 等の状態やイベントフローには触れず、QRコード読み取りの成否と完全に独立させる。
    private fun extractSerialFromImage(inputImage: InputImage) {
        val recognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        recognizer.process(inputImage)
            .addOnSuccessListener { text ->
                SerialExtractor.extract(text)?.let { serial ->
                    copyToClipboard(serial, showToast = false)
                    Toast.makeText(
                        this,
                        getString(R.string.serial_copied_to_clipboard, serial),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .addOnFailureListener { exception ->
                Log.w(TAG, "Serial OCR failed", exception)
            }
            .addOnCompleteListener { recognizer.close() }
    }

    private fun copyToClipboard(text: String, showToast: Boolean = true) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("QR code", text))
        // Android 13+ はシステムがコピー確認 UI を表示するため二重通知を避ける
        if (showToast && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
        }
    }

    // カメラでのライブ文字列スキャン（応募シリアル）を開始するオプション機能。
    // QRスキャン（GmsBarcodeScanner）とは別ボタン・別画面で完全に独立させ、既存のQR起動処理には触れない。
    private fun startSerialScanning() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.onSerialScanStarted()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun onSerialConfirmedFromCamera(serial: String) {
        viewModel.onSerialScanFinished()
        copyToClipboard(serial, showToast = false)
        Toast.makeText(this, getString(R.string.serial_copied_to_clipboard, serial), Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}

@Composable
fun QrScannerScreen(
    uiState: MainUiState,
    modifier: Modifier = Modifier,
    onStartScan: () -> Unit = {},
    onHistoryItemClick: (String) -> Unit = {},
    onCopy: (String) -> Unit = {},
    onRetryModuleInstall: () -> Unit = {},
    onStartSerialScan: () -> Unit = {},
    onSerialConfirmed: (String) -> Unit = {},
    onCancelSerialScan: () -> Unit = {}
) {
    when {
        uiState.moduleError != null -> ModuleErrorContent(
            modifier = modifier,
            errorMessage = uiState.moduleError,
            onRetry = onRetryModuleInstall
        )
        uiState.isScanning -> ScanningContent(modifier = modifier)
        uiState.isScanningSerial -> SerialCameraScreen(
            modifier = modifier,
            onConfirm = onSerialConfirmed,
            onCancel = onCancelSerialScan
        )
        else -> IdleContent(
            modifier = modifier,
            uiState = uiState,
            onStartScan = onStartScan,
            onHistoryItemClick = onHistoryItemClick,
            onCopy = onCopy,
            onStartSerialScan = onStartSerialScan
        )
    }
}

private const val MAX_SERIAL_CANDIDATES = 5

// カメラのライブ映像から「応募シリアル」欄をOCRで読み取るオプション機能の画面。
// OCRは誤認識（o/0 の混同など）が起こり得るため、自動確定はせず認識結果を候補として
// 一覧表示し、ユーザーが選択・修正してから明示的に確定（コピー）してもらう。
@Composable
private fun SerialCameraScreen(
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    BackHandler(onBack = onCancel)

    val candidates = remember { mutableStateListOf<String>() }
    var editedValue by remember { mutableStateOf("") }

    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }

    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val recognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        val processingFrame = AtomicBoolean(false)
        var cameraProvider: ProcessCameraProvider? = null

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            cameraProvider = provider
            bindSerialScanUseCases(
                provider = provider,
                lifecycleOwner = lifecycleOwner,
                previewView = previewView,
                executor = executor,
                recognizer = recognizer,
                processingFrame = processingFrame,
                onCandidateFound = { candidate ->
                    if (!candidates.contains(candidate)) {
                        candidates.add(0, candidate)
                        while (candidates.size > MAX_SERIAL_CANDIDATES) {
                            candidates.removeAt(candidates.lastIndex)
                        }
                        if (editedValue.isEmpty()) {
                            editedValue = candidate
                        }
                    }
                }
            )
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            cameraProvider?.unbindAll()
            recognizer.close()
            executor.shutdown()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { previewView })
            Text(
                text = stringResource(R.string.serial_scan_hint),
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp)
            )
        }
        SerialCandidatePanel(
            candidates = candidates,
            editedValue = editedValue,
            onCandidateSelected = { editedValue = it },
            onValueChange = { editedValue = it },
            onCancel = onCancel,
            onConfirm = { onConfirm(editedValue) }
        )
    }
}

// 認識候補の選択・修正・確定用パネル。カメラに依存しないため単独でPreview可能。
@Composable
private fun SerialCandidatePanel(
    candidates: List<String>,
    editedValue: String,
    onCandidateSelected: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(modifier = modifier, tonalElevation = 2.dp) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (candidates.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.serial_scan_candidates_label),
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(Modifier.height(4.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(candidates) { candidate ->
                        OutlinedButton(onClick = { onCandidateSelected(candidate) }) {
                            Text(text = candidate)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            OutlinedTextField(
                value = editedValue,
                onValueChange = onValueChange,
                label = { Text(text = stringResource(R.string.serial_scan_value_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onCancel) {
                    Text(text = stringResource(R.string.serial_scan_cancel))
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onConfirm,
                    enabled = editedValue.isNotBlank()
                ) {
                    Text(text = stringResource(R.string.serial_scan_confirm))
                }
            }
        }
    }
}

// ImageAnalysis から生の Image を取り出す ImageProxy.image は実験的APIだが、
// ここでフレームごとに閉じるライフサイクルを自前で管理しているため安全に利用できる。
@SuppressLint("UnsafeOptInUsageError")
private fun bindSerialScanUseCases(
    provider: ProcessCameraProvider,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    executor: java.util.concurrent.ExecutorService,
    recognizer: com.google.mlkit.vision.text.TextRecognizer,
    processingFrame: AtomicBoolean,
    onCandidateFound: (String) -> Unit
) {
    val preview = Preview.Builder().build().also {
        it.surfaceProvider = previewView.surfaceProvider
    }
    val analysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()
    analysis.setAnalyzer(executor) { imageProxy ->
        val mediaImage = imageProxy.image
        if (mediaImage == null || !processingFrame.compareAndSet(false, true)) {
            imageProxy.close()
            return@setAnalyzer
        }
        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        recognizer.process(inputImage)
            .addOnSuccessListener { text ->
                SerialExtractor.extract(text)?.let(onCandidateFound)
            }
            .addOnCompleteListener {
                processingFrame.set(false)
                imageProxy.close()
            }
    }

    try {
        provider.unbindAll()
        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
    } catch (e: Exception) {
        Log.e("SerialCameraScreen", "Camera bind failed", e)
    }
}

@Composable
private fun ScanningContent(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(text = stringResource(R.string.scanning_in_progress))
    }
}

@Composable
private fun ModuleErrorContent(
    errorMessage: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = stringResource(R.string.module_unavailable, errorMessage))
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Text(text = stringResource(R.string.retry))
        }
    }
}

@Composable
private fun IdleContent(
    uiState: MainUiState,
    onStartScan: () -> Unit,
    onHistoryItemClick: (String) -> Unit,
    onCopy: (String) -> Unit,
    onStartSerialScan: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Button(
            onClick = onStartScan,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = stringResource(R.string.tap_to_scan))
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onStartSerialScan,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = stringResource(R.string.scan_serial_button))
        }

        uiState.lastScannedValue?.let { value ->
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.last_scan_result),
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(Modifier.height(4.dp))
            Surface(
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = value,
                        modifier = Modifier
                            .weight(1f)
                            .padding(12.dp),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    TextButton(onClick = { onCopy(value) }) {
                        Text(text = stringResource(R.string.copy))
                    }
                }
            }
        }

        if (uiState.history.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.history_title),
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(Modifier.height(4.dp))
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(uiState.history) { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onHistoryItemClick(item) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = item,
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 12.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        TextButton(onClick = { onCopy(item) }) {
                            Text(text = stringResource(R.string.copy))
                        }
                    }
                }
            }
        }
    }
}

// SerialCameraScreen 自体はCameraXの実カメラに依存するためPreview対象から除外し、
// カメラに依存しない候補選択パネル（SerialCandidatePanel）のみPreviewする。

@ComposePreview(showBackground = true, name = "Idle")
@Composable
private fun QrScannerScreenIdlePreview() {
    QuickQrReaderTheme {
        QrScannerScreen(uiState = MainUiState())
    }
}

@ComposePreview(showBackground = true, name = "Idle - Dark")
@Composable
private fun QrScannerScreenIdleDarkPreview() {
    QuickQrReaderTheme(darkTheme = true) {
        QrScannerScreen(uiState = MainUiState())
    }
}

@ComposePreview(showBackground = true, name = "Scanning")
@Composable
private fun QrScannerScreenScanningPreview() {
    QuickQrReaderTheme {
        QrScannerScreen(uiState = MainUiState(isScanning = true))
    }
}

@ComposePreview(showBackground = true, name = "With Result And History")
@Composable
private fun QrScannerScreenWithResultPreview() {
    QuickQrReaderTheme {
        QrScannerScreen(
            uiState = MainUiState(
                lastScannedValue = "https://example.com/some/long/path?query=value",
                history = listOf(
                    "https://example.com/some/long/path?query=value",
                    "WIFI:S:MyNetwork;T:WPA;P:secret;;",
                    "Hello World"
                )
            )
        )
    }
}

@ComposePreview(showBackground = true, name = "Module Error")
@Composable
private fun QrScannerScreenModuleErrorPreview() {
    QuickQrReaderTheme {
        QrScannerScreen(uiState = MainUiState(moduleError = "network error"))
    }
}

@ComposePreview(showBackground = true, name = "Serial Candidates")
@Composable
private fun SerialCandidatePanelPreview() {
    QuickQrReaderTheme {
        SerialCandidatePanel(
            candidates = listOf("mpk39838", "mpko9838", "hpk39838"),
            editedValue = "mpk39838",
            onCandidateSelected = {},
            onValueChange = {},
            onCancel = {},
            onConfirm = {}
        )
    }
}

@ComposePreview(showBackground = true, name = "Serial Candidates - Empty")
@Composable
private fun SerialCandidatePanelEmptyPreview() {
    QuickQrReaderTheme {
        SerialCandidatePanel(
            candidates = emptyList(),
            editedValue = "",
            onCandidateSelected = {},
            onValueChange = {},
            onCancel = {},
            onConfirm = {}
        )
    }
}
