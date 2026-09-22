package com.ks.app.quickqrreader.ui

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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview as ComposePreview
import androidx.compose.ui.unit.dp
import com.ks.app.quickqrreader.R
import com.ks.app.quickqrreader.ui.theme.QuickQrReaderTheme

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
