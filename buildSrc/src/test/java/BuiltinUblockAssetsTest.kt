/* Copyright (C) 2026 CitrusBrowser contributors. Apache License 2.0. */
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BuiltinUblockAssetsTest {
    @get:Rule val temp = TemporaryFolder()
    private val version = "1.76.4"
    private val url = "https://addons.mozilla.org/fixture.xpi"
    private val source = "https://github.com/gorhill/uBlock/tree/1.76.4"
    private val manifest = """{"version":"1.76.4","browser_specific_settings":{"gecko":{"id":"uBlock0@raymondhill.net"}}}""".toByteArray()

    private fun archive(extra: Map<String, ByteArray> = emptyMap(), required: Boolean = true): File = temp.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            val files = linkedMapOf<String, ByteArray>()
            if (required) { files["manifest.json"] = manifest; files["LICENSE.txt"] = "license fixture\n".toByteArray() }
            files.putAll(extra)
            files.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        }
    }
    private fun generate(zip: File, out: File, sha: String = BuiltinUblockAssets.hash(zip)) =
        BuiltinUblockAssets.generate(zip, out, version, sha, url, source)

    @Test fun originalManifestLicensesAndSignatureEntriesArePreservedAndGenerationIsDeterministic() {
        val signature = byteArrayOf(1, 2, 3, 4)
        val zip = archive(mapOf("META-INF/mozilla.rsa" to signature, "lib/example/LICENSE" to "third-party fixture".toByteArray()))
        val out = File(temp.root, "generated")
        generate(zip, out)
        assertArrayEquals(manifest, File(out, "ublock/manifest.json").readBytes())
        assertArrayEquals(signature, File(out, "ublock/META-INF/mozilla.rsa").readBytes())
        assertEquals("third-party fixture", File(out, "ublock/lib/example/LICENSE").readText())
        val notice = File(out, "ublock-provenance.txt").readBytes()
        assertTrue(notice.toString(Charsets.UTF_8).contains(source))
        assertTrue(notice.toString(Charsets.UTF_8).contains(BuiltinUblockAssets.hash(zip)))
        generate(zip, out)
        assertArrayEquals(notice, File(out, "ublock-provenance.txt").readBytes())
    }

    @Test fun hashFailureKeepsPreviouslyGeneratedAssets() {
        val out = temp.newFolder(); File(out, "keep.txt").writeText("old")
        assertThrows(IOException::class.java) { generate(archive(), out, "0".repeat(64)) }
        assertEquals("old", File(out, "keep.txt").readText())
    }

    @Test fun zipSlipAndNormalizedDuplicatePathsAreRejectedWithoutPublishingPartialAssets() {
        val out = temp.newFolder(); File(out, "keep.txt").writeText("old")
        for (extra in listOf(mapOf("../escape" to byteArrayOf(1)), mapOf("folder/" to byteArrayOf(), "folder" to byteArrayOf(1)),
            mapOf("C:/escape" to byteArrayOf(1)), mapOf("folder\\escape" to byteArrayOf(1)))) {
            assertThrows(IOException::class.java) { generate(archive(extra), out) }
            assertEquals("old", File(out, "keep.txt").readText())
            assertFalse(File(out, "ublock").exists())
        }
    }

    @Test fun directoryPayloadAndOversizedEntriesAreRejected() {
        val out = File(temp.root, "generated")
        assertThrows(IOException::class.java) { generate(archive(mapOf("payload/" to ByteArray(1000))), out) }
        assertThrows(IOException::class.java) { generate(archive(mapOf("large" to ByteArray(16 * 1024 * 1024 + 1))), out) }
        assertFalse(out.exists())
    }

    @Test fun mandatoryLicenseManifestAndPinnedIdentityAreChecked() {
        val out = File(temp.root, "generated")
        val fixtures = listOf(archive(mapOf("manifest.json" to manifest), required = false), archive(emptyMap(), required = false),
            archive(mapOf("manifest.json" to """{"version":"9.0","id":"uBlock0@raymondhill.net"}""".toByteArray())))
        fixtures.forEach { zip -> assertThrows(IOException::class.java) { generate(zip, out) } }
        assertFalse(out.exists())
    }

    @Test fun verifiedCacheWorksOfflineAndCorruptCacheNeverFallsBackToUncheckedBytes() {
        val zip = archive()
        val expected = BuiltinUblockAssets.hash(zip)
        BuiltinUblockAssets.fetch("https://never-request.invalid/", expected, zip, offline = true)
        zip.appendText("corrupt")
        assertThrows(org.gradle.api.GradleException::class.java) {
            BuiltinUblockAssets.fetch("https://never-request.invalid/", expected, zip, offline = true)
        }
    }
}
