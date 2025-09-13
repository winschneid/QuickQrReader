package com.ks.app.quickqrreader

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels // Required for by viewModels()
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
// import androidx.compose.runtime.LaunchedEffect // Not strictly needed for this version
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.ks.app.quickqrreader.ui.MainViewModel
import com.ks.app.quickqrreader.ui.MainUiState
import com.ks.app.quickqrreader.ui.theme.QuickQrReaderTheme
import kotlinx.coroutines.launch // Added import for launch
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

        installModuleIfNeeded() // This can also be moved to ViewModel later

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })

        // Collect events from ViewModel
        lifecycleScope.launch { // Ensure this launch is from kotlinx.coroutines
            viewModel.eventFlow
                .flowWithLifecycle(lifecycle, androidx.lifecycle.Lifecycle.State.STARTED)
                .onEach { event ->
                    when (event) {
                        is MainViewModel.ViewEvent.StartActivity -> {
                            try {
                                startActivity(event.intent)
                            } catch (e: Exception) {
                                Toast.makeText(this@MainActivity, "Cannot start activity: ${e.message}", Toast.LENGTH_SHORT).show()
                                startScanning() // Attempt to restart scanning
                            }
                        }
                        is MainViewModel.ViewEvent.ShowToast -> {
                            Toast.makeText(this@MainActivity, event.message, Toast.LENGTH_SHORT).show()
                            startScanning() // Attempt to restart scanning after error message
                        }
                    }
                }.launchIn(lifecycleScope) // Changed 'this' to 'lifecycleScope'
        }

        setContent {
            QuickQrReaderTheme {
                val uiState by viewModel.uiState.collectAsState()
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    QrScannerScreen(
                        modifier = Modifier.padding(innerPadding),
                        uiState = uiState
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        startScanning()
    }

    private fun installModuleIfNeeded() {
        val moduleInstall = ModuleInstall.getClient(this)
        val moduleInstallRequest = ModuleInstallRequest.newBuilder()
            .addApi(GmsBarcodeScanning.getClient(this))
            .build()

        moduleInstall.installModules(moduleInstallRequest)
            .addOnSuccessListener {
                if (it.areModulesAlreadyInstalled()) {
                    // Modules are already installed or no update is required.
                } else {
                    Toast.makeText(this, getString(R.string.module_install_success), Toast.LENGTH_LONG).show()
                }
            }
            .addOnFailureListener { exception ->
                Toast.makeText(this, getString(R.string.module_install_failed, exception.message), Toast.LENGTH_LONG).show()
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
                startScanning() // Restart scanning on cancellation
            }
            .addOnFailureListener { exception ->
                viewModel.onScanFailed(exception)
            }
    }
}

@Composable
fun QrScannerScreen(
    modifier: Modifier = Modifier,
    uiState: MainUiState
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (uiState.isScanning) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(text = stringResource(R.string.scanning_in_progress))
        } else {
            Text(
                text = stringResource(R.string.qr_scanner_ready),
                modifier = Modifier.padding(16.dp)
            )
            Text(
                text = stringResource(R.string.scanning_starts_automatically),
                modifier = Modifier.padding(16.dp)
            )
        }
        // Removed uiState.userMessage?.let block as userMessage is no longer in MainUiState
    }
}
