package com.ks.app.quickqrreader

import android.content.Intent
import android.net.Uri
import org.junit.Test
import org.junit.Assert.*

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }
    
    @Test
    fun lineUrlDetection_isCorrect() {
        val lineUrl = "https://line.me/ti/p/example"
        assertTrue("LINE URL should be detected", lineUrl.contains("line.me"))
        
        val normalUrl = "https://www.google.com"
        assertFalse("Normal URL should not be detected as LINE", normalUrl.contains("line.me"))
    }
    
    @Test
    fun uriParsing_isCorrect() {
        val validUrl = "https://www.example.com"
        val uri = Uri.parse(validUrl)
        assertNotNull("URI should be parsed correctly", uri)
        assertEquals("Scheme should be https", "https", uri.scheme)
        assertEquals("Host should be www.example.com", "www.example.com", uri.host)
    }
}