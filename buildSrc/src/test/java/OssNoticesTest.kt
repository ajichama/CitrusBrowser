import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OssNoticesTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun root(): File = temporary.newFolder().apply {
        File(this, "oss").mkdirs()
        File(this, "html/license").mkdirs()
        File(this, "oss/sources.xml").writeText("<sources version=\"1\"/>")
        File(this, "html/license/fixLicensesHtml.css").writeText("a { word-break: break-all; }\n")
    }

    private fun document(root: File, text: String, name: String = "license.txt", kind: String = "LICENSE"): OssDocument {
        val canonical = OssNotices.canonicalText(text.toByteArray())
        File(root, "oss/$name").writeBytes(canonical)
        return OssDocument(kind, "oss/$name", OssNotices.hash(canonical), "test/$name")
    }

    @Test fun runtimeSelectionExcludesObsoleteMetadataAndPreservesNotices() {
        val root = root()
        val license = document(root, "Copyright A\nPermission A")
        val notice = document(root, "Copyright B\nAttribution B", "notice.txt", "NOTICE")
        OssNotices.writeComponents(File(root, "oss/dependencies.xml"), listOf(
            OssComponent("test:current:2", "現在の依存", "https://example.com", listOf(OssLicense("License A", "https://example.com/license")), listOf(license, notice)),
            OssComponent("com.google.firebase:removed:1", "削除済みFirebase", "", emptyList(), emptyList())
        ))
        val generated = OssNotices.generate(root, listOf("test:current:2"))
        assertTrue(generated.missing.isEmpty())
        assertTrue(generated.html.contains("Copyright A"))
        assertTrue(generated.html.contains("Copyright B"))
        assertFalse(generated.html.contains("Firebase"))
    }

    @Test fun unknownComponentAndMissingBodyAreExplicit() {
        val root = root()
        OssNotices.writeComponents(File(root, "oss/dependencies.xml"), listOf(
            OssComponent("test:empty:1", "情報不足", "", listOf(OssLicense("Unspecified", "")), emptyList())))
        val generated = OssNotices.generate(root, listOf("test:unknown:1", "test:empty:1"))
        assertEquals(listOf("test:empty:1: LICENSE本文なし", "test:unknown:1: metadataなし"), generated.missing)
        assertFalse(generated.html.contains("Apache"))
        assertFalse(generated.html.contains("MIT"))
    }

    @Test fun emptyLicenseFileDoesNotCountAsComplete() {
        val root = root()
        OssNotices.writeComponents(File(root, "oss/dependencies.xml"), listOf(
            OssComponent("test:blank:1", "空本文", "", emptyList(), listOf(document(root, "\n  \n")))))
        assertEquals(listOf("test:blank:1: LICENSE本文なし"), OssNotices.generate(root, listOf("test:blank:1")).missing)
    }

    @Test fun htmlEscapesMetadataAndTextAndRejectsExecutableLinks() {
        val root = root()
        OssNotices.writeComponents(File(root, "oss/dependencies.xml"), listOf(
            OssComponent("test:unsafe:1", "<script>&\"'", "javascript:alert(1)",
                listOf(OssLicense("<license>", "https://example.com/?x=\"&y=<")),
                listOf(document(root, "<script>alert('x')</script> & copyright")))))
        val html = OssNotices.generate(root, listOf("test:unsafe:1")).html
        assertFalse(html.contains("<script>"))
        assertFalse(html.contains("href=\"javascript:"))
        assertTrue(html.contains("&lt;script&gt;"))
        assertTrue(html.contains("&amp; copyright"))
    }

    @Test fun customSourceHtmlPreservesNyslInsteadOfInventingBsd() {
        val root = root()
        val text = "<section>Copyright Cynthia Project<br>NYSL Version 0.9982</section>\n"
        val file = File(root, "html/license/MikanBrowserLicense.html").apply { writeText(text) }
        val sha = OssNotices.hash(file.readBytes())
        File(root, "oss/sources.xml").writeText("<sources version=\"1\"><source id=\"mikan\" html=\"html/license/MikanBrowserLicense.html\" sha256=\"$sha\"/></sources>")
        OssNotices.writeComponents(File(root, "oss/dependencies.xml"), emptyList())
        val html = OssNotices.generate(root, emptyList()).html
        assertTrue(html.contains("Copyright Cynthia Project"))
        assertTrue(html.contains("NYSL Version 0.9982"))
        assertFalse(html.contains("BSD"))
    }

    @Test fun crlfAndLfInputsHaveIdenticalVerifiedTextAndGzip() {
        val root = root()
        val doc = document(root, "Copyright\nPermission\n")
        val file = File(root, doc.path)
        val expected = OssNotices.verifiedText(root, doc.path, doc.sha256)
        file.writeText("Copyright\r\nPermission\r\n")
        assertEquals(expected, OssNotices.verifiedText(root, doc.path, doc.sha256))
        fun gzip(): ByteArray = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(expected.toByteArray()) }
        }.toByteArray()
        assertArrayEquals(gzip(), gzip())
        assertEquals(expected, GZIPInputStream(gzip().inputStream()).reader().use { it.readText() })
    }

    @Test fun hashMismatchAndPathEscapeFailClosed() {
        val root = root()
        val doc = document(root, "Copyright")
        File(root, doc.path).appendText(" changed")
        assertThrows(IllegalArgumentException::class.java) { OssNotices.verifiedText(root, doc.path, doc.sha256) }
        assertThrows(IllegalArgumentException::class.java) { OssNotices.checkedFile(root, "../outside") }
    }

    @Test fun doctypeAndExternalEntityAreRejected() {
        val payload = "<!DOCTYPE components [<!ENTITY xxe SYSTEM 'file:///not-readable'>]><components version=\"1\">&xxe;</components>"
        assertThrows(Exception::class.java) { OssNotices.xml(payload.byteInputStream()) }
    }

    @Test fun interruptedWriteRetainsOldPublishedFile() {
        val root = root()
        val file = File(root, "existing.txt").apply { writeText("previous") }
        assertThrows(IOException::class.java) {
            OssNotices.writeAtomic(file) { output ->
                output.write("partial".toByteArray())
                throw IOException("中断")
            }
        }
        assertEquals("previous", file.readText())
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".oss-") })
        OssNotices.writeAtomic(file, "next".toByteArray())
        assertEquals("next", file.readText())
    }

    @Test fun aarExtractsNestedJarLicenseAndNoticeWithoutLosingEitherCopyright() {
        fun archiveBytes(entries: Map<String, String>): ByteArray = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip -> entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
            } }
        }.toByteArray()
        val aar = File(temporary.root, "test.aar")
        ZipOutputStream(aar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("classes.jar"))
            zip.write(archiveBytes(mapOf("META-INF/LICENSE.txt" to "Copyright A", "META-INF/NOTICE" to "Copyright B")))
            zip.closeEntry()
        }
        val docs = OssMetadataCollector.extractDocuments(aar).toMap()
        assertEquals("Copyright A", docs["classes.jar!/META-INF/LICENSE.txt"]?.toString(Charsets.UTF_8))
        assertEquals("Copyright B", docs["classes.jar!/META-INF/NOTICE"]?.toString(Charsets.UTF_8))
    }

    @Test fun nestedIgnoredEntryCannotExpandWithoutLimit() {
        val nested = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("ignored.class"))
                zip.write(ByteArray(4096))
                zip.closeEntry()
            }
        }.toByteArray()
        val aar = File(temporary.root, "bounded.aar")
        ZipOutputStream(aar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("classes.jar")); zip.write(nested); zip.closeEntry()
        }
        assertThrows(IllegalArgumentException::class.java) {
            OssMetadataCollector.extractDocuments(aar, maxExpandedBytes = 2048)
        }
    }

    @Test fun multipleNoticesShareRetentionAndEntryBudgets() {
        val jar = File(temporary.root, "bounded.jar")
        ZipOutputStream(jar.outputStream()).use { zip ->
            listOf("LICENSE-a", "LICENSE-b").forEach {
                zip.putNextEntry(ZipEntry(it)); zip.write(ByteArray(20)); zip.closeEntry()
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            OssMetadataCollector.extractDocuments(jar, maxRetainedBytes = 30)
        }
        assertThrows(IllegalArgumentException::class.java) {
            OssMetadataCollector.extractDocuments(jar, maxEntries = 1)
        }
    }

    @Test fun failedTextDownloadIsNotRetriedForEveryDependency() {
        var calls = 0
        val cache = OssTextDownloadCache { calls++; null }
        assertNull(cache.get("https://example.com/unavailable"))
        assertNull(cache.get("https://example.com/unavailable"))
        assertEquals(1, calls)
    }

    @Test fun knownApacheApiUsesOfficialPlainTextWithoutGuessingOtherLicenses() {
        val expected = "https://www.apache.org/licenses/LICENSE-2.0.txt"
        assertEquals(expected, OssNotices.licenseTextUrl("https://api.github.com/licenses/apache-2.0"))
        assertEquals(expected, OssNotices.licenseTextUrl("http://www.apache.org/licenses/LICENSE-2.0"))
        val unknown = "https://example.com/unknown-license"
        assertEquals(unknown, OssNotices.licenseTextUrl(unknown))
    }

    @Test fun downloadedLicenseRejectsJsonXmlHtmlAndBinaryPayloads() {
        assertThrows(IllegalArgumentException::class.java) {
            OssNotices.plainLicenseText("application/json", "{\"body\":\"license\"}".toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            OssNotices.plainLicenseText("text/plain", "{\"body\":\"license\"}".toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            OssNotices.plainLicenseText("application/xml", "<license>text</license>".toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            OssNotices.plainLicenseText("text/plain", "<license>text</license>".toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            OssNotices.plainLicenseText("text/plain", "<!DOCTYPE html><html>page</html>".toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            OssNotices.plainLicenseText("application/octet-stream", byteArrayOf(0, 1, 2))
        }
        assertArrayEquals("Copyright\nPermission".toByteArray(),
            OssNotices.plainLicenseText("text/plain; charset=utf-8", "Copyright\r\nPermission".toByteArray()))
    }
}
