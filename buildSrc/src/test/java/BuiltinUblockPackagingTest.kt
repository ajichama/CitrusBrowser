/* Copyright (C) 2026 CitrusBrowser contributors. Apache License 2.0. */
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BuiltinUblockPackagingTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun assets(): File = temporary.newFolder().apply {
        mapOf("ublock/manifest.json" to "manifest", "ublock/LICENSE.txt" to "license",
            "ublock/_locales/so/messages.json" to "日本語・locale fixture",
            "ublock/META-INF/mozilla.rsa" to "signature", "ublock-provenance.txt" to "source notice")
            .forEach { (name, text) -> File(this, name).apply { parentFile.mkdirs(); writeText(text) } }
    }

    private fun apk(assets: File, omit: String? = null, replace: Pair<String, ByteArray>? = null, extra: String? = null): File =
        temporary.newFile("fixture-${System.nanoTime()}.apk").also { target ->
            ZipOutputStream(target.outputStream()).use { zip ->
                assets.walkTopDown().filter { it.isFile }.forEach { file ->
                    val name = "assets/" + file.relativeTo(assets).invariantSeparatorsPath
                    if (name != omit) {
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(if (replace?.first == name) replace.second else file.readBytes())
                        zip.closeEntry()
                    }
                }
                listOfNotNull("assets/licenses.compressed", "classes.dex", extra).forEach { name ->
                    zip.putNextEntry(ZipEntry(name)); zip.write(byteArrayOf(1, 2)); zip.closeEntry()
                }
            }
        }

    @Test fun compressedApkPreservesAllLocaleSignatureAndProvenanceBytes() {
        val source = assets()
        assertEquals(5, BuiltinUblockPackaging.verify(source, apk(source)))
    }

    @Test fun aaptLocaleExclusionIsDetectedEvenWhenManifestAndSignatureRemain() {
        val source = assets()
        val error = assertThrows(IOException::class.java) {
            BuiltinUblockPackaging.verify(source, apk(source, omit = "assets/ublock/_locales/so/messages.json"))
        }
        assertTrue(error.message!!.contains("_locales/so/messages.json"))
    }

    @Test fun sameSizeByteChangeAndUnexpectedExtensionFileAreRejected() {
        val source = assets()
        assertThrows(IOException::class.java) {
            BuiltinUblockPackaging.verify(source, apk(source, replace = "assets/ublock/manifest.json" to "Manifest".toByteArray()))
        }
        assertThrows(IOException::class.java) {
            BuiltinUblockPackaging.verify(source, apk(source, extra = "assets/ublock/unknown.js"))
        }
    }
}
