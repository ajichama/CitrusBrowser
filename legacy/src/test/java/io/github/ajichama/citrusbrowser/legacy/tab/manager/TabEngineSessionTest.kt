/* Copyright (C) 2026 CitrusBrowser contributors. Apache License 2.0. */
package io.github.ajichama.citrusbrowser.legacy.tab.manager

import io.github.ajichama.citrusbrowser.webview.CustomWebView
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class TabEngineSessionTest {
    @Test fun restoredIndexAndActionStateAreNotReplacedByTheAdapter() {
        val web = mock(CustomWebView::class.java)
        val index = TabIndexData("https://example.com/restored", "保存済みタブ", 0, 73L, 42L)
        index.setNavLock(true)
        index.setPinning(true)
        val tab = TabData(web, index)
        assertSame(tab.engineSession, tab.engineSession)
        assertSame(web, tab.mWebView)
        assertSame(index, tab.tabIndexData)
        assertEquals(73L, tab.id)
        assertEquals(42L, tab.parent)
        assertEquals("https://example.com/restored", tab.url)
        assertTrue(tab.isNavLock)
        assertTrue(tab.isPinning)
        verifyNoInteractions(web)
    }

    @Test fun progressOneHundredDoesNotTurnStopIntoReloadBeforeTheExistingFinishedEvent() {
        val web = mock(CustomWebView::class.java)
        `when`(web.url).thenReturn("https://example.com/")
        val tab = TabData(web, TabIndexData())
        tab.onPageStarted("https://example.com/", null)
        tab.onProgressChanged(100)
        assertTrue(tab.isInPageLoad)
        assertTrue(tab.engineSession.state.isLoading)
        tab.onPageFinished(web, "https://example.com/")
        assertFalse(tab.isInPageLoad)
        assertFalse(tab.engineSession.state.isLoading)
    }

    @Test fun separateTabsKeepIndependentStateAndTheChosenEngineReceivesTheOperation() {
        val firstWeb = mock(CustomWebView::class.java)
        val secondWeb = mock(CustomWebView::class.java)
        val first = TabData(firstWeb, TabIndexData())
        val second = TabData(secondWeb, TabIndexData())
        first.setInPageLoad(true)
        assertNotSame(first.engineSession, second.engineSession)
        assertTrue(first.engineSession.state.isLoading)
        assertFalse(second.engineSession.state.isLoading)
        first.engineSession.reload()
        verify(firstWeb).reload()
        verify(secondWeb, never()).reload()
        verify(firstWeb, never()).webView
        verify(secondWeb, never()).webView
    }
}
