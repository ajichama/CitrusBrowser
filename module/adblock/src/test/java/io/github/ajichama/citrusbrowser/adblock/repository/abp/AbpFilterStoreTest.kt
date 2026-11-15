package io.github.ajichama.citrusbrowser.adblock.repository.abp

import io.github.ajichama.citrusbrowser.adblock.core.AbpLoader
import io.github.ajichama.citrusbrowser.adblock.filter.abp.ABP_PREFIX_ALLOW
import io.github.ajichama.citrusbrowser.adblock.filter.abp.ABP_PREFIX_DENY
import io.github.ajichama.citrusbrowser.adblock.filter.abp.AbpFilterDecoder
import io.github.ajichama.citrusbrowser.adblock.filter.unified.io.FilterWriter
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal class TestPublication : AbpFilterStore.Publication {
    var failNext = false
    var discardNext = false
    override fun recover(file: File): File? = file.takeIf { it.exists() }
    override fun commit(staged: File, file: File) {
        if (failNext) { failNext = false; throw IOException("テスト用の保存失敗") }
        if (discardNext) { discardNext = false; return }
        Files.move(staged.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
    override fun delete(file: File) { file.delete() }
}

class AbpFilterStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val url = "https://filters.example/list.txt"

    @Test
    fun openedLoaderKeepsAllCategoriesFromTheSameGeneration() {
        val dir = temporary.newFolder()
        val store = AbpFilterStore(dir, TestPublication())
        store.write(1, url, decode("||old.example^\n@@||old-allowed.example^"))
        AbpLoader(dir, listOf(AbpEntity(entityId = 1, url = url)), store).use { loader ->
            store.write(1, url, decode("||new.example^\n@@||new-allowed.example^"))
            assertEquals("old.example", loader.loadAll(ABP_PREFIX_DENY).single().pattern)
            assertEquals("old-allowed.example", loader.loadAll(ABP_PREFIX_ALLOW).single().pattern)
        }
        AbpLoader(dir, listOf(AbpEntity(entityId = 1, url = url)), store).use { loader ->
            assertEquals("new.example", loader.loadAll(ABP_PREFIX_DENY).single().pattern)
            assertEquals("new-allowed.example", loader.loadAll(ABP_PREFIX_ALLOW).single().pattern)
        }
    }

    @Test
    fun failedPublicationPreservesThePreviousBundleAndDetectsSilentFailure() {
        val dir = temporary.newFolder()
        val publication = TestPublication()
        val store = AbpFilterStore(dir, publication)
        store.write(1, url, decode("||old.example^"))
        for (silent in listOf(false, true)) {
            publication.failNext = !silent
            publication.discardNext = silent
            assertThrows(IOException::class.java) { store.write(1, url, decode("||new.example^")) }
            AbpLoader(dir, listOf(AbpEntity(entityId = 1, url = url)), store).use { loader ->
                assertEquals("old.example", loader.loadAll(ABP_PREFIX_DENY).single().pattern)
            }
            assertFalse(dir.listFiles()!!.any { it.extension == "tmp" })
        }
    }

    @Test
    fun emptyCategoriesAndAssetMetadataAreACompleteCurrentBundle() {
        val dir = temporary.newFolder()
        val store = AbpFilterStore(dir, TestPublication())
        store.write(1, url, decode(""), "asset-1")
        assertTrue(store.isCurrent(1, url, "asset-1"))
        assertFalse(store.isCurrent(1, url, "asset-2"))
        assertFalse(store.isCurrent(1, "https://different.example/list.txt"))
        AbpLoader(dir, listOf(AbpEntity(entityId = 1, url = url)), store).use {
            assertEquals(0, it.loadAll(ABP_PREFIX_DENY).count())
            assertEquals(0, it.loadAll(ABP_PREFIX_ALLOW).count())
            assertEquals(0, it.loadAllElementFilter().count())
        }
    }

    @Test
    fun legacyFallbackIsUsedOnlyWhenNoBundleExistsAndNeverForDisabledSubscriptions() {
        val dir = temporary.newFolder()
        File(dir, "b_1").outputStream().use { FilterWriter().write(it, decode("||legacy.example^").blackList) }
        val store = AbpFilterStore(dir, TestPublication())
        val entity = AbpEntity(entityId = 1, url = url)
        AbpLoader(dir, listOf(entity), store).use {
            assertEquals("legacy.example", it.loadAll(ABP_PREFIX_DENY).single().pattern)
        }
        File(dir, "abp_1.zip").writeText("壊れた ZIP")
        assertFalse(store.isCurrent(1, url))
        AbpLoader(dir, listOf(entity), store).use { assertFalse(it.loadAll(ABP_PREFIX_DENY).any()) }
        File(dir, "abp_1.zip").delete()
        entity.enabled = false
        AbpLoader(dir, listOf(entity), store).use { assertFalse(it.loadAll(ABP_PREFIX_DENY).any()) }
        store.delete(1)
        assertFalse(File(dir, "b_1").exists())
    }

    private fun decode(rules: String) = AbpFilterDecoder().decode(rules.reader().buffered(), url)
}
