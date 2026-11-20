package io.github.ajichama.citrusbrowser.core.utility.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class UniqueFileNameTest {
    private val temporarySuffix = ".citrusdownload"

    @Test
    fun unfinishedDownloadReservesItsFinalName() {
        val existing = setOf("sample.zip$temporarySuffix")
        assertEquals("sample-1.zip", createUniqueFileName("sample.zip", temporarySuffix, existing::contains))
    }

    @Test
    fun completedAndUnfinishedNamesAreBothSkippedWhenNumbering() {
        val existing = setOf("sample.zip", "sample-1.zip$temporarySuffix", "sample-2.zip")
        assertEquals("sample-3.zip", createUniqueFileName("sample.zip", temporarySuffix, existing::contains))
    }

    @Test
    fun availableNameIsKeptAndOtherTemporaryFilesDoNotReserveIt() {
        val existing = setOf("other.zip$temporarySuffix")
        assertEquals("sample.zip", createUniqueFileName("sample.zip", temporarySuffix, existing::contains))
    }

    @Test
    fun unfinishedDownloadWithoutAnExtensionAlsoReservesItsName() {
        val existing = setOf("sample$temporarySuffix")
        assertEquals("sample-1", createUniqueFileName("sample", temporarySuffix, existing::contains))
    }
}
