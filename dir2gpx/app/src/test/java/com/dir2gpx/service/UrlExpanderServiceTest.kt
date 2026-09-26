package com.dir2gpx.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlExpanderServiceTest {

    @Test
    fun testExtractFromAndroidAppIntentUri() {
        val intentUri = "android-app://com.google.android.apps.maps/https/www.google.com/maps/dir/37.7749,-122.4194/37.3382,-121.8863"
        val extracted = UrlExpanderService.extractFromIntentUri(intentUri)

        assertEquals(
            "https://www.google.com/maps/dir/37.7749,-122.4194/37.3382,-121.8863",
            extracted
        )
    }

    @Test
    fun testExtractFromGenericIntentUri() {
        val intentUri = "intent://maps.google.com/maps/dir/37.7749,-122.4194/37.3382,-121.8863#Intent;scheme=https;package=com.google.android.apps.maps;end"
        val extracted = UrlExpanderService.extractFromIntentUri(intentUri)

        assertEquals(
            "https://maps.google.com/maps/dir/37.7749,-122.4194/37.3382,-121.8863",
            extracted
        )
    }

    @Test
    fun testExtractFromRegularHttpUrlReturnsNull() {
        val regularUrl = "https://www.google.com/maps/dir/37.7749,-122.4194/37.3382,-121.8863"
        val extracted = UrlExpanderService.extractFromIntentUri(regularUrl)

        assertNull("Regular HTTP URL should return null from intent extractor", extracted)
    }
}
