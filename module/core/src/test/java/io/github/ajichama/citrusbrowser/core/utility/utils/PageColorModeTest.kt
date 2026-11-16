package io.github.ajichama.citrusbrowser.core.utility.utils

import org.junit.Assert.*
import org.junit.Test

class PageColorModeTest {
    @Test fun existingValuesAndLegacyForceDarkKeepTheirSupportedMeaning() {
        assertEquals(PageColorMode.LIGHT, PageColorMode.normalize(0))
        assertEquals(PageColorMode.SITE_DARK, PageColorMode.normalize(1))
        assertEquals(PageColorMode.AUTO_DARK, PageColorMode.normalize(2))
        assertEquals(PageColorMode.AUTO_DARK, PageColorMode.normalize(3))
    }

    @Test fun siteUnspecifiedDoesNotBecomeALightOverride() {
        assertEquals(PageColorMode.INHERIT, PageColorMode.normalizeOverride(-1))
        assertEquals(PageColorMode.AUTO_DARK, PageColorMode.normalizeOverride(3))
        assertEquals(PageColorMode.INHERIT, PageColorMode.normalizeOverride(4))
        assertEquals(PageColorMode.INHERIT, PageColorMode.normalizeOverride(Int.MIN_VALUE))
    }

    @Test fun damagedGlobalValueFallsBackToLight() {
        assertEquals(PageColorMode.LIGHT, PageColorMode.normalize(4))
        assertEquals(PageColorMode.LIGHT, PageColorMode.normalize(-1))
    }

    @Test fun onlyAutomaticModeAllowsAlgorithmicDarkening() {
        assertFalse(PageColorMode.isDark(PageColorMode.LIGHT))
        assertTrue(PageColorMode.isDark(PageColorMode.SITE_DARK))
        assertFalse(PageColorMode.allowsAlgorithmicDarkening(PageColorMode.SITE_DARK))
        assertTrue(PageColorMode.allowsAlgorithmicDarkening(PageColorMode.AUTO_DARK))
        assertTrue(PageColorMode.allowsAlgorithmicDarkening(3))
    }
}
