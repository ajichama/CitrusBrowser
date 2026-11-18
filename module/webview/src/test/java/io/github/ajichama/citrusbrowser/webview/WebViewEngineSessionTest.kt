/* Copyright (C) 2026 CitrusBrowser contributors. Apache License 2.0. */
package io.github.ajichama.citrusbrowser.webview

import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class WebViewEngineSessionTest {
    @Test fun navigationDelegatesExactlyOnceToTheHistoryOwningWrapperWithoutUnwrapping() {
        val web = mock(CustomWebView::class.java)
        `when`(web.webView).thenThrow(AssertionError("履歴wrapperを展開してはいけません。"))
        val session = WebViewEngineSession(web) { false }
        session.goBack()
        session.goForward()
        session.reload()
        session.stopLoading()
        inOrder(web).apply {
            verify(web).goBack()
            verify(web).goForward()
            verify(web).reload()
            verify(web).stopLoading()
        }
        verify(web, never()).webView
        verifyNoMoreInteractions(web)
    }

    @Test fun snapshotsReadCurrentHistoryAndLoadingAndDoNotMutateEarlierSnapshots() {
        val web = mock(CustomWebView::class.java)
        var canGoBack = false
        var canGoForward = true
        var loading = false
        `when`(web.canGoBack()).thenAnswer { canGoBack }
        `when`(web.canGoForward()).thenAnswer { canGoForward }
        val session = WebViewEngineSession(web) { loading }
        val before = session.state
        canGoBack = true
        canGoForward = false
        loading = true
        val after = session.state
        assertFalse(before.canGoBack)
        assertTrue(before.canGoForward)
        assertFalse(before.isLoading)
        assertTrue(after.canGoBack)
        assertFalse(after.canGoForward)
        assertTrue(after.isLoading)
        verify(web, never()).progress
    }

    @Test fun engineFailureIsPropagatedWithoutRetryOrSuccessfulFallback() {
        val web = mock(CustomWebView::class.java)
        val failure = IllegalStateException("終了した描画先")
        doThrow(failure).`when`(web).reload()
        val thrown = assertThrows(IllegalStateException::class.java) { WebViewEngineSession(web) { false }.reload() }
        assertSame(failure, thrown)
        verify(web, times(1)).reload()
        verifyNoMoreInteractions(web)
    }
}
