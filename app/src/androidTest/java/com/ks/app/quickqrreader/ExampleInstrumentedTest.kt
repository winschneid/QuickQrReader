package com.ks.app.quickqrreader

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ks.app.quickqrreader.ui.theme.QuickQrReaderTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    
    @get:Rule
    val composeTestRule = createComposeRule()
    
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.ks.app.quickqrreader", appContext.packageName)
    }
    
    @Test
    fun qrScannerScreenDisplaysCorrectTexts() {
        composeTestRule.setContent {
            QuickQrReaderTheme {
                QrScannerScreen()
            }
        }
        
        composeTestRule
            .onNodeWithText("QR Code Scanner Ready")
            .assertIsDisplayed()
            
        composeTestRule
            .onNodeWithText("Scanning will start automatically...")
            .assertIsDisplayed()
    }
}