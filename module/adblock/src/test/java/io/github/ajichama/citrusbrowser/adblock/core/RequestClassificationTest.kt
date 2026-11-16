package io.github.ajichama.citrusbrowser.adblock.core

import android.net.Uri
import android.webkit.WebResourceRequest
import io.github.ajichama.citrusbrowser.adblock.getContentRequest
import io.github.ajichama.citrusbrowser.adblock.filter.abp.AbpFilterDecoder
import io.github.ajichama.citrusbrowser.adblock.filter.unified.whenever
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock

class RequestClassificationTest {
    private fun type(path: String = "/resource", headers: Map<String, String> = emptyMap(), main: Boolean = false, scheme: String = "https") =
        RequestClassification.contentType(main, scheme, path, headers)

    @Test fun mainFrameIsOnlyDocumentRegardlessOfPathHeadersOrOldPageUrl() {
        assertEquals(ContentRequest.TYPE_DOCUMENT, type("/image.png", mapOf("Sec-Fetch-Dest" to "image"), main = true))
        val url = uri("https://new.example/page.js")
        val request = mock(WebResourceRequest::class.java)
        whenever(request.url).thenReturn(url)
        whenever(request.isForMainFrame).thenReturn(true)
        whenever(request.requestHeaders).thenReturn(emptyMap())
        assertEquals(ContentRequest.TYPE_DOCUMENT, request.getContentRequest(uri("https://old.example/")).type)
    }

    @Test fun explicitDestinationsOverrideMisleadingSuffixesAndHeaderCase() {
        val expected = mapOf("iframe" to ContentRequest.TYPE_SUB_DOCUMENT, "image" to ContentRequest.TYPE_IMAGE,
            "script" to ContentRequest.TYPE_SCRIPT, "worker" to ContentRequest.TYPE_SCRIPT,
            "style" to ContentRequest.TYPE_STYLE_SHEET, "font" to ContentRequest.TYPE_FONT,
            "audio" to ContentRequest.TYPE_MEDIA, "video" to ContentRequest.TYPE_MEDIA)
        expected.forEach { (destination, mask) ->
            assertEquals(destination, mask, type("/misleading.json", mapOf("sEc-FeTcH-DeSt" to destination)))
        }
        assertEquals(ContentRequest.TYPE_IMAGE, type("/misleading.js", mapOf("Sec-Fetch-Dest" to "image")))
    }

    @Test fun ordinarySubresourcesAreNotFramesAndUnknownIsOnlyOther() {
        assertEquals(ContentRequest.TYPE_IMAGE, type("/picture.png"))
        assertEquals(ContentRequest.TYPE_SCRIPT, type("/code.js"))
        assertEquals(ContentRequest.TYPE_OTHER, type())
        assertEquals(ContentRequest.TYPE_OTHER, type(headers = mapOf("Accept" to "*/*")))
    }

    @Test fun explicitXhrIsExclusiveAndJsonIsNotAScript() {
        assertEquals(ContentRequest.TYPE_XHR, type("/payload.js", mapOf("x-requested-with" to "XMLHttpRequest")))
        assertEquals(ContentRequest.TYPE_XHR, type("/payload.json", mapOf("Sec-Fetch-Dest" to "empty", "X-Requested-With" to "XMLHttpRequest")))
        assertEquals(ContentRequest.TYPE_OTHER, type("/payload.json", mapOf("Accept" to "application/json")))
        assertEquals(ContentRequest.TYPE_OTHER, type("/payload.js", mapOf("Sec-Fetch-Dest" to "empty")))
        assertEquals(ContentRequest.TYPE_OTHER, type("/payload.js", mapOf("Sec-Fetch-Dest" to "object")))
    }

    @Test fun acceptIsCaseInsensitiveIgnoresParametersAndDoesNotGuessMixedUses() {
        assertEquals(ContentRequest.TYPE_STYLE_SHEET, type(headers = mapOf("accept" to "Text/CSS; charset=utf-8")))
        assertEquals(ContentRequest.TYPE_IMAGE, type(headers = mapOf("Accept" to "image/avif,image/webp")))
        assertEquals(ContentRequest.TYPE_IMAGE, type(headers = mapOf("Accept" to "text/css;q=0,image/png;q=1")))
        assertEquals(ContentRequest.TYPE_OTHER, type(headers = mapOf("Accept" to "text/css,image/png")))
        assertEquals(ContentRequest.TYPE_OTHER, type(headers = mapOf("Accept" to "text/html")))
    }

    @Test fun websocketAndSuffixFallbackKeepOneType() {
        assertEquals(ContentRequest.TYPE_WEB_SOCKET, type("/resource.js", scheme = "WSS"))
        assertEquals(ContentRequest.TYPE_SCRIPT, type("/code.MJS"))
        assertEquals(ContentRequest.TYPE_FONT, type("/font.woff2"))
        assertEquals(ContentRequest.TYPE_MEDIA, type("/video.webm"))
        assertEquals(ContentRequest.TYPE_OTHER, type("/code.js/download"))
    }

    @Test fun pslFallbackNeverEquatesDifferentSingleLabelHostsOrIpv6Addresses() {
        assertTrue(RequestClassification.isThirdParty("localhost", "intranet"))
        assertTrue(RequestClassification.isThirdParty("[2001:db8::1]", "[2001:db8::2]"))
        assertTrue(RequestClassification.isThirdParty("192.0.2.1", "192.0.2.2"))
        assertFalse(RequestClassification.isThirdParty("[2001:0db8:0:0:0:0:0:1]", "2001:db8::1"))
        assertFalse(RequestClassification.isThirdParty("localhost", "LOCALHOST."))
    }

    @Test fun canonicalHostsAndRegistrableDomainsRetainPslAndPrivateSuffixBoundaries() {
        assertFalse(RequestClassification.isThirdParty("cdn.EXAMPLE.co.jp.", "www.example.co.jp"))
        assertTrue(RequestClassification.isThirdParty("first.co.jp", "second.co.jp"))
        assertTrue(RequestClassification.isThirdParty("first.github.io", "second.github.io"))
        assertFalse(RequestClassification.isThirdParty("bücher.example", "xn--bcher-kva.example"))
        assertTrue(RequestClassification.isThirdParty(null, "example.com"))
        assertTrue(RequestClassification.isThirdParty("", "example.com"))
    }

    @Test fun typedRulesDoNotBlockOrAllowUnrelatedResourceTypes() {
        val decoded = AbpFilterDecoder().decode("||cdn.example^\$subdocument\n@@||cdn.example^\$image".reader().buffered(), null)
        val blocker = Blocker(FilterContainer(), FilterContainer().also { c -> decoded.whiteList.forEach(c::plusAssign) },
            FilterContainer().also { c -> decoded.blackList.forEach(c::plusAssign) })
        val resource = uri("https://cdn.example/asset")
        val page = uri("https://page.example/")
        assertNull(blocker.isBlock(ContentRequest(resource, page, type("/asset.js"), true)))
        assertNotNull(blocker.isBlock(ContentRequest(resource, page, type(headers = mapOf("Sec-Fetch-Dest" to "iframe")), true)))
        val broad = AbpFilterDecoder().decode("||cdn.example^\n@@||cdn.example^\$image".reader().buffered(), null)
        val broadBlocker = Blocker(FilterContainer(), FilterContainer().also { c -> broad.whiteList.forEach(c::plusAssign) },
            FilterContainer().also { c -> broad.blackList.forEach(c::plusAssign) })
        assertNotNull(broadBlocker.isBlock(ContentRequest(resource, page, type(), true)))
    }

    private fun uri(value: String): Uri = mock(Uri::class.java).also {
        val parsed = java.net.URI(value)
        whenever(it.toString()).thenReturn(value)
        whenever(it.host).thenReturn(parsed.host)
        whenever(it.path).thenReturn(parsed.path)
        whenever(it.scheme).thenReturn(parsed.scheme)
        whenever(it.schemeSpecificPart).thenReturn(parsed.rawSchemeSpecificPart)
    }
}
