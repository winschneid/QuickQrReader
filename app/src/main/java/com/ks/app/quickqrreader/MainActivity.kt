package com.ks.app.quickqrreader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.ks.app.quickqrreader.ui.theme.QuickQrReaderTheme

class MainActivity : ComponentActivity() {
    private lateinit var scanner: GmsBarcodeScanner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
            
        scanner = GmsBarcodeScanning.getClient(this, options)
        
        installModuleIfNeeded()
        
        setContent {
            QuickQrReaderTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    QrScannerScreen(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Start scanning when app becomes active
        startScanning()
    }
    
    private fun installModuleIfNeeded() {
        val moduleInstall = ModuleInstall.getClient(this)
        val moduleInstallRequest = ModuleInstallRequest.newBuilder()
            .addApi(GmsBarcodeScanning.getClient(this))
            .build()
        
        moduleInstall.installModules(moduleInstallRequest)
    }
    
    private fun startScanning() {
        scanner.startScan()
            .addOnSuccessListener { barcode ->
                barcode.rawValue?.let { qrCode ->
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(qrCode))
                    try {
                        startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(this, "Cannot open: $qrCode", Toast.LENGTH_SHORT).show()
                        // Restart scanning for next QR code
                        startScanning()
                    }
                }
            }
            .addOnCanceledListener {
                // User canceled, restart scanning
                startScanning()
            }
            .addOnFailureListener { exception ->
                Toast.makeText(this, "Scan failed: ${exception.message}", Toast.LENGTH_SHORT).show()
                // Restart scanning on failure
                startScanning()
            }
    }
}

@Composable
fun QrScannerScreen(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "QR Code Scanner Ready",
            modifier = Modifier.padding(16.dp)
        )
        
        Text(
            text = "Scanning will start automatically...",
            modifier = Modifier.padding(16.dp)
        )
    }
}