package com.ks.app.quickqrreader

import android.Manifest
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
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
import com.ks.app.quickqrreader.domain.QrTextDecoder
import com.ks.app.quickqrreader.domain.SerialExtractor
import com.ks.app.quickqrreader.ui.MainViewModel
import com.ks.app.quickqrreader.ui.QrScannerScreen
import com.ks.app.quickqrreader.ui.theme.QuickQrReaderTheme
import kotlinx.coroutines.launch

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

        // 未処理イベントは uiState に残っているので、STARTED の間だけ取り出して処理する。
        // 取り出し（onEventsHandled）と処理（handleViewEvent）の間に中断点を置かないこと。
        // コレクターが解除されるのは中断点だけなので、この区間が途中で切れることはなく、
        // 「イベントを消したのに処理しなかった」も「処理したのに消えていない」も起きない。
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    val pending = state.pendingEvents
                    if (pending.isEmpty()) return@collect
                    viewModel.onEventsHandled(pending.map { it.id })
                    pending.forEach { handleViewEvent(it.event) }
                }
            }
        }

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
        // 自動スキャンはコールドスタート時のみ。無条件に開始すると、読み取り成功直後の
        // onResume（スキャナーが閉じた瞬間）に新しいスキャンが始まって起動したブラウザの上に
        // GMS スキャナーが被さり、外部アプリから戻るたびにも勝手にカメラが開いて
        // 読み取り結果や履歴を確認できなくなる。次のスキャンは待機画面のボタンから。
        if (viewModel.consumeAutoScanRequest()) {
            startScanning()
        }
    }

    private fun handleViewEvent(event: MainViewModel.ViewEvent) {
        when (event) {
            is MainViewModel.ViewEvent.StartActivity -> {
                try {
                    startActivity(event.intent)
                    viewModel.onExternalAppLaunched()
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
                viewModel.onScanSuccess(QrTextDecoder.decode(barcode.rawValue, barcode.rawBytes, barcode.displayValue))
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
                val value = barcodes.firstNotNullOfOrNull {
                    QrTextDecoder.decode(it.rawValue, it.rawBytes, it.displayValue)
                }
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
