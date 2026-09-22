package com.ks.app.quickqrreader.ui

import android.annotation.SuppressLint
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.tooling.preview.Preview as ComposePreview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.ks.app.quickqrreader.R
import com.ks.app.quickqrreader.domain.SerialExtractor
import com.ks.app.quickqrreader.ui.theme.QuickQrReaderTheme
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val MAX_SERIAL_CANDIDATES = 5

// カメラのライブ映像から「応募シリアル」欄をOCRで読み取るオプション機能の画面。
// OCRは誤認識（o/0 の混同など）が起こり得るため、自動確定はせず認識結果を候補として
// 一覧表示し、ユーザーが選択・修正してから明示的に確定（コピー）してもらう。
@Composable
internal fun SerialCameraScreen(
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
