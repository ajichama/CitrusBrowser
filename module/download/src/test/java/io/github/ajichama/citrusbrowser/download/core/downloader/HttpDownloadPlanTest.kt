package io.github.ajichama.citrusbrowser.download.core.downloader

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class HttpDownloadPlanTest {
    @Test
    fun freshDownloadCannotAppendToAnUnrelatedTemporaryFile() {
        val offset = httpResumeOffset(isResume = false, resumable = true, partialFileSize = 3)
        assertEquals(0L, offset)
        assertRejected { httpDownloadPlan(206, "bytes 3-5/6", 3, offset) }
        assertEquals(HttpDownloadPlan(false, 0, 6), httpDownloadPlan(200, null, 6, offset))
    }

    @Test
    fun explicitResumeUsesItsPartialFileAndValidatesTheReturnedSuffix() {
        val offset = httpResumeOffset(isResume = true, resumable = true, partialFileSize = 3)
        assertEquals(3L, offset)
        assertEquals(HttpDownloadPlan(true, 3, 6), httpDownloadPlan(206, "bytes 3-5/6", 3, offset))
    }

    @Test
    fun nonResumableOrEmptyFilesAlwaysRestartFromZero() {
        assertEquals(0L, httpResumeOffset(isResume = true, resumable = false, partialFileSize = 3))
        assertEquals(0L, httpResumeOffset(isResume = true, resumable = true, partialFileSize = 0))
        assertEquals(0L, httpResumeOffset(isResume = true, resumable = true, partialFileSize = -1))
    }

    @Test
    fun rangeIgnoredRestartsFromZeroWithFullResponseSize() {
        assertEquals(HttpDownloadPlan(false, 0, 6), httpDownloadPlan(200, null, 6, 3))
    }

    @Test
    fun partialResponseAppendsAtTheRequestedOffsetWithTotalSize() {
        assertEquals(HttpDownloadPlan(true, 3, 6), httpDownloadPlan(206, "bytes 3-5/6", 3, 3))
    }

    @Test
    fun partialResponseWithoutContentLengthUsesContentRange() {
        assertEquals(HttpDownloadPlan(true, 3, 6), httpDownloadPlan(206, "bytes 3-5/6", -1, 3))
    }

    @Test
    fun fullResponseWithoutContentLengthRemainsUnknown() {
        assertEquals(HttpDownloadPlan(false, 0, -1), httpDownloadPlan(200, null, -1, 3))
    }

    @Test
    fun invalidRangesAreRejectedBeforeWriting() {
        val invalidRanges = listOf(
            null,
            "bytes 0-5/6",
            "bytes 3-4/6",
            "bytes 3-6/6",
            "bytes 3-2/6",
            "bytes 3-5/*",
            "bytes 3-5/99999999999999999999999999999",
            "items 3-5/6",
        )
        invalidRanges.forEach { range ->
            assertRejected { httpDownloadPlan(206, range, -1, 3) }
        }
    }

    @Test
    fun contentLengthMustMatchTheReturnedRange() {
        assertRejected { httpDownloadPlan(206, "bytes 3-5/6", 2, 3) }
        assertRejected { httpDownloadPlan(206, "bytes 3-5/6", 4, 3) }
    }

    @Test
    fun unsolicitedPartialResponseAndRangeRejectionAreNotSaved() {
        assertRejected { httpDownloadPlan(206, "bytes 0-5/6", 6, 0) }
        assertRejected { httpDownloadPlan(416, "bytes */3", 0, 3) }
        assertRejected { httpDownloadPlan(500, null, 0, 3) }
    }

    private fun assertRejected(action: () -> Unit) {
        try {
            action()
            fail("不正な部分応答を受け入れました")
        } catch (_: IOException) {
            // 書き込み前の拒否を確認する。
        }
    }
}
