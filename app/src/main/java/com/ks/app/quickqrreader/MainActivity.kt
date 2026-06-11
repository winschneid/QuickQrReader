package com.ks.app.quickqrreader

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
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
import com.ks.app.quickqrreader.ui.MainUiState
import com.ks.app.quickqrreader.ui.MainViewModel
import com.ks.app.quickqrreader.ui.theme.QuickQrReaderTheme
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class MainActivity : ComponentActivity() {
    private lateinit var scanner: GmsBarcodeScanner
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
                        }
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

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("QR code", text))
        // Android 13+ はシステムがコピー確認 UI を表示するため二重通知を避ける
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
fun QrScannerScreen(
    uiState: MainUiState,
    modifier: Modifier = Modifier,
    onStartScan: () -> Unit = {},
    onHistoryItemClick: (String) -> Unit = {},
    onCopy: (String) -> Unit = {},
    onRetryModuleInstall: () -> Unit = {}
) {
    when {
        uiState.moduleError != null -> ModuleErrorContent(
            modifier = modifier,
            errorMessage = uiState.moduleError,
            onRetry = onRetryModuleInstall
        )
        uiState.isScanning -> ScanningContent(modifier = modifier)
        else -> IdleContent(
            modifier = modifier,
            uiState = uiState,
            onStartScan = onStartScan,
            onHistoryItemClick = onHistoryItemClick,
            onCopy = onCopy
        )
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
