package com.ks.app.quickqrreader

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.ks.app.quickqrreader.ui.MainUiState
import com.ks.app.quickqrreader.ui.theme.QuickQrReaderTheme
import io.github.takahirom.roborazzi.RoborazziRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w320dp-h568dp-xhdpi")
class QrScannerScreenshotTest {
    
    @get:Rule
    val composeTestRule = createComposeRule()
    
    @get:Rule
    val roborazziRule = RoborazziRule(
        options = RoborazziRule.Options(
            outputDirectoryPath = "src/test/screenshots"
        )
    )
    
    @Test
    fun qrScannerScreen_screenshot() {
        composeTestRule.setContent {
            QuickQrReaderTheme {
                QrScannerScreen(uiState = MainUiState())
            }
        }
        
        composeTestRule
            .onRoot()
            .captureRoboImage("QrScannerScreen")
    }
    
    @Test
    fun qrScannerScreen_darkTheme_screenshot() {
        composeTestRule.setContent {
            QuickQrReaderTheme(darkTheme = true) {
                QrScannerScreen(uiState = MainUiState())
            }
        }
        
        composeTestRule
            .onRoot()
            .captureRoboImage("QrScannerScreen_Dark")
    }
    
    @Test
    fun qrScannerScreen_scanning_screenshot() {
        composeTestRule.setContent {
            QuickQrReaderTheme {
                QrScannerScreen(uiState = MainUiState(isScanning = true))
            }
        }
        
        composeTestRule
            .onRoot()
            .captureRoboImage("QrScannerScreen_Scanning")
    }
}