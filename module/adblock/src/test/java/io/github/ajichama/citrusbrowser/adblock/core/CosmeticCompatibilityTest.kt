package io.github.ajichama.citrusbrowser.adblock.core

import android.net.Uri
import io.github.ajichama.citrusbrowser.adblock.filter.abp.AbpFilterDecoder
import io.github.ajichama.citrusbrowser.adblock.filter.abp.ABP_PREFIX_DENY
import io.github.ajichama.citrusbrowser.adblock.filter.unified.UnifiedFilter
import io.github.ajichama.citrusbrowser.adblock.filter.unified.element.ElementContainer
import io.github.ajichama.citrusbrowser.adblock.filter.unified.element.ElementFilter
import io.github.ajichama.citrusbrowser.adblock.filter.unified.element.GroupElementFilter
import io.github.ajichama.citrusbrowser.adblock.filter.unified.io.*
import io.github.ajichama.citrusbrowser.adblock.filter.unified.whenever
import io.github.ajichama.citrusbrowser.adblock.repository.abp.AbpEntity
import io.github.ajichama.citrusbrowser.adblock.repository.abp.AbpFilterStore
import io.github.ajichama.citrusbrowser.adblock.repository.abp.TestPublication
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CosmeticCompatibilityTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun cosmeticExceptionsKeepHighBitsAndDoNotWhitelistNetworkRequests() {
        val decoded = decode("@@||example.com^\$elemhide,generichide", "@@||other.test^\$ghide")
        assertTrue(decoded.blackList.isEmpty())
        assertTrue(decoded.whiteList.isEmpty())
        val restored = roundTrip(decoded.elementDisableFilter)
        assertEquals(0x6000_0000, restored[0].contentType)
        assertEquals(ContentRequest.TYPE_ELEMENT_GENERIC_HIDE, restored[1].contentType)
        val request = ContentRequest(uri("https://example.com/a.js"), uri("https://example.com/"), ContentRequest.TYPE_SCRIPT, false)
        assertFalse(restored[0].isMatch(request))
    }

    @Test
    fun mixedNetworkAndCosmeticOptionsUseSeparateObjectsAndMasks() {
        val decoded = decode("@@||example.com^\$script,elemhide")
        assertEquals(ContentRequest.TYPE_SCRIPT, decoded.whiteList.single().contentType)
        assertEquals(ContentRequest.TYPE_ELEMENT_HIDE, decoded.elementDisableFilter.single().contentType)
        assertNotSame(decoded.whiteList.single(), decoded.elementDisableFilter.single())
        val unsupported = decode("||example.com^\$elemhide", "@@||example.com^\$script,~generichide", "@@||example.com^\$elemhide=1")
        assertTrue(unsupported.blackList.isEmpty())
        assertTrue(unsupported.whiteList.isEmpty())
        assertTrue(unsupported.elementDisableFilter.isEmpty())
    }

    @Test
    fun legacyNetworkFixtureUsesLittleEndian16BitMaskWithoutPromotingLostBits() {
        val legacy = readFilters(legacyNetwork("ads", 0x0006))
        assertEquals(ContentRequest.TYPE_SCRIPT or ContentRequest.TYPE_IMAGE, legacy.single().contentType)
        assertTrue(legacy.single().isMatch(ContentRequest(uri("https://cdn.example/ADS.js"), uri("https://example.com/"), ContentRequest.TYPE_SCRIPT, true)))
        val lostMask = readFilters(legacyNetwork("example.com", 0))
        assertEquals(0, lostMask.single().contentType)
        assertFalse(lostMask.single().isMatch(ContentRequest(uri("https://example.com/"), uri("https://example.com/"), ContentRequest.TYPE_ELEMENT_HIDE, false)))
    }

    @Test
    fun legacyElementFixtureRetainsExclusionAndStillAppliesOnOtherHosts() {
        val restored = readElements(legacyElements("blocked.example", ".legacy", excluded = true))
        assertTrue(restored.single().isNot)
        val cosmetic = cosmetic(restored)
        assertNotNull(cosmetic.loadScript(uri("https://other.test/")))
        assertNull(cosmetic.loadScript(uri("https://blocked.example/")))
        assertNull(cosmetic.loadScript(uri("https://other.test/"), ignoreGeneric = true))
    }

    @Test
    fun unknownCacheVersionsAreRejected() {
        val bad = legacyNetwork("ads", 6).also { it[10] = 99 }
        assertFalse(FilterReader(ByteArrayInputStream(bad)).checkHeader())
        val badElement = legacyElements("example.com", ".ad").also { it[10] = 99 }
        assertFalse(ElementReader(ByteArrayInputStream(badElement)).checkHeader())
    }

    @Test
    fun elemhideWinsOverGenerichideInEitherRegistrationOrderAfterRoundTrip() {
        val hiding = decode("##.generic", "example.com##.specific").elementList
        val disable = decode("@@||example.com^\$elemhide", "@@||example.com^\$generichide").elementDisableFilter
        for (ordered in listOf(disable, disable.reversed())) {
            assertNull(cosmetic(hiding, roundTrip(ordered)).loadScript(uri("https://example.com/")))
        }
    }

    @Test
    fun generichideDisablesGenericRulesAndKeepsPositiveDomainGroups() {
        val rules = decode("##.generic", "~blocked.test,~private.test##.negative", "example.com,other.test##.specific")
        val disable = decode("@@||example.com^\$generichide").elementDisableFilter
        val script = cosmetic(roundTripElements(rules.elementList), roundTrip(disable)).loadScript(uri("https://example.com/"))!!
        assertTrue(script.contains(".specific"))
        assertFalse(script.contains(".generic"))
        assertFalse(script.contains(".negative"))
    }

    @Test
    fun ignoreGenericSettingChangesTheCachedScriptForTheSameUri() {
        val url = uri("https://example.com/")
        val cosmetic = cosmetic(decode("##.generic", "example.com##.specific").elementList)
        assertTrue(cosmetic.loadScript(url)!!.contains(".generic"))
        val specific = cosmetic.loadScript(url, ignoreGeneric = true)!!
        assertTrue(specific.contains(".specific"))
        assertFalse(specific.contains(".generic"))
        assertTrue(cosmetic.loadScript(url, ignoreGeneric = false)!!.contains(".generic"))
    }

    @Test
    fun groupedExclusionAppliesOnlyToItsOwnLineAfterRoundTrip() {
        val scoped = decode("example.com,~sub.example.com##.ad").elementList
        assertTrue(scoped.single() is GroupElementFilter)
        val restored = roundTripElements(scoped)
        assertEquals("example.com,~sub.example.com", restored.single().domain)
        assertNotNull(cosmetic(restored).loadScript(uri("https://news.example.com/")))
        assertNull(cosmetic(restored).loadScript(uri("https://sub.example.com/")))
        val independent = restored + decode("sub.example.com##.ad").elementList
        assertTrue(cosmetic(independent).loadScript(uri("https://sub.example.com/"))!!.contains(".ad"))
        val genericIndependent = restored + decode("##.ad").elementList
        assertTrue(cosmetic(genericIndependent).loadScript(uri("https://sub.example.com/"))!!.contains(".ad"))
    }

    @Test
    fun exclusionOnlyGroupsApplyEverywhereExceptExcludedDomains() {
        val rules = roundTripElements(decode("~example.com,~other.test##.ad").elementList)
        assertTrue(rules.single().isGeneric)
        val cosmetic = cosmetic(rules)
        assertNotNull(cosmetic.loadScript(uri("https://outside.test/")))
        assertNull(cosmetic.loadScript(uri("https://sub.example.com/")))
        assertNull(cosmetic.loadScript(uri("https://other.test/")))
        assertNull(cosmetic.loadScript(uri("https://outside.test/"), ignoreGeneric = true))
    }

    @Test
    fun aPositiveGroupIsIndexedForAllItsDomainsWithoutDuplicateSelectors() {
        val rules = roundTripElements(decode("alpha.example,beta.test##.ad").elementList)
        val cosmetic = cosmetic(rules)
        assertNotNull(cosmetic.loadScript(uri("https://alpha.example/"), ignoreGeneric = true))
        val script = cosmetic.loadScript(uri("https://beta.test/"), ignoreGeneric = true)!!
        assertEquals(1, Regex("'\\.ad'").findAll(script).count())
        assertNull(cosmetic.loadScript(uri("https://unrelated.test/")))
    }

    @Test
    fun aScopedExplicitExceptionDoesNotBecomeAnExceptionOnExcludedPages() {
        val rules = roundTripElements(decode("##.ad", "example.com,~sub.example.com#@#.ad").elementList)
        val cosmetic = cosmetic(rules)
        assertNull(cosmetic.loadScript(uri("https://example.com/")))
        assertTrue(cosmetic.loadScript(uri("https://sub.example.com/"))!!.contains(".ad"))
        assertTrue(cosmetic.loadScript(uri("https://unrelated.test/"))!!.contains(".ad"))
    }

    @Test
    fun groupedEntityConditionsPreserveDomainBoundariesAndExclusions() {
        val rules = roundTripElements(decode("example.*,~private.example.*##.entity").elementList)
        val cosmetic = cosmetic(rules)
        assertNotNull(cosmetic.loadScript(uri("https://news.example.co.jp/")))
        assertNull(cosmetic.loadScript(uri("https://private.example.co.jp/")))
        assertNull(cosmetic.loadScript(uri("https://notexample.com/")))
    }

    @Test
    fun theMostSpecificDomainControlsGroupedHidingAfterRoundTrip() {
        val rules = roundTripElements(decode(
            "example.com,~sub.example.com,news.sub.example.com,~private.news.sub.example.com##.ad",
        ).elementList)
        val cosmetic = cosmetic(rules)
        for (host in listOf("example.com", "news.sub.example.com", "child.news.sub.example.com")) {
            assertNotNull(host, cosmetic.loadScript(uri("https://$host/")))
        }
        for (host in listOf("sub.example.com", "child.sub.example.com", "private.news.sub.example.com", "child.private.news.sub.example.com", "unrelated.test")) {
            assertNull(host, cosmetic.loadScript(uri("https://$host/")))
        }
    }

    @Test
    fun theMostSpecificDomainControlsGroupedExceptionsAfterRoundTrip() {
        val rules = roundTripElements(decode(
            "##.ad",
            "example.com,~sub.example.com,news.sub.example.com,~private.news.sub.example.com#@#.ad",
        ).elementList)
        val cosmetic = cosmetic(rules)
        for (host in listOf("example.com", "news.sub.example.com", "child.news.sub.example.com")) {
            assertNull(host, cosmetic.loadScript(uri("https://$host/")))
        }
        for (host in listOf("sub.example.com", "child.sub.example.com", "private.news.sub.example.com", "child.private.news.sub.example.com", "unrelated.test")) {
            assertTrue(host, cosmetic.loadScript(uri("https://$host/"))!!.contains(".ad"))
        }
    }

    @Test
    fun oldBundleRemainsReadableUntilANewParserBundleIsPublished() {
        val dir = temporary.newFolder()
        val url = "https://filters.example/list.txt"
        ZipOutputStream(File(dir, "abp_1.zip").outputStream()).use { zip ->
            val emptyNetwork = "YZBABPFI\u0000\u0001\u0001".toByteArray() + byteArrayOf(0, 0, 0, 0)
            val emptyElements = "YZBABPEF\u0000\u0001\u0001".toByteArray() + byteArrayOf(0, 0, 0, 0)
            for ((name, bytes) in listOf("b_" to legacyNetwork("old.example", 0xffff), "w_" to emptyNetwork, "wp_" to emptyNetwork, "e_" to emptyElements)) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("manifest.properties"))
            Properties().apply {
                setProperty("format", "1"); setProperty("parserRevision", "2"); setProperty("sourceUrl", url); setProperty("commitId", "legacy-fixture")
            }.store(zip, null)
            zip.closeEntry()
        }
        val store = AbpFilterStore(dir, TestPublication())
        val entity = AbpEntity(entityId = 1, url = url)
        assertFalse(store.isCurrent(1, url))
        AbpLoader(dir, listOf(entity), store).use { assertEquals("old.example", it.loadAll(ABP_PREFIX_DENY).single().pattern) }
        store.write(1, url, decode("||new.example^", "example.com,~sub.example.com##.ad"))
        assertTrue(store.isCurrent(1, url))
    }

    private fun decode(vararg lines: String) = AbpFilterDecoder().decode(lines.joinToString("\n").reader().buffered(), null)
    private fun cosmetic(elements: List<ElementFilter>, disables: List<UnifiedFilter> = emptyList()) = CosmeticFiltering(
        FilterContainer().also { container -> disables.forEach { container += it } },
        ElementContainer().also { container -> elements.forEach { container += it } },
    )
    private fun roundTrip(filters: List<UnifiedFilter>) = ByteArrayOutputStream().let { output ->
        FilterWriter().write(output, filters); readFilters(output.toByteArray())
    }
    private fun roundTripElements(filters: List<ElementFilter>) = ByteArrayOutputStream().let { output ->
        ElementWriter().write(output, filters); readElements(output.toByteArray())
    }
    private fun readFilters(bytes: ByteArray): List<UnifiedFilter> = FilterReader(ByteArrayInputStream(bytes)).let {
        assertTrue(it.checkHeader()); it.readAll().toList()
    }
    private fun readElements(bytes: ByteArray): List<ElementFilter> = ElementReader(ByteArrayInputStream(bytes)).let {
        assertTrue(it.checkHeader()); it.readAll().toList()
    }
    private fun uri(value: String): Uri {
        val parsed = URI(value)
        return Mockito.mock(Uri::class.java).also {
            whenever(it.toString()).thenReturn(value)
            whenever(it.host).thenReturn(parsed.host)
            whenever(it.schemeSpecificPart).thenReturn(parsed.rawSchemeSpecificPart)
        }
    }

    // v1 fixture は本番 writer を使わず、旧公開形式のリトルエンディアン値を直接組み立てる。
    private fun legacyNetwork(pattern: String, mask: Int) = ByteArrayOutputStream().apply {
        write("YZBABPFI\u0000\u0001\u0001".toByteArray())
        write(byteArrayOf(1, 0, 0, 0, 1, (mask and 255).toByte(), ((mask ushr 8) and 255).toByte(), 1, -1))
        writeString(pattern)
        write(0)
    }.toByteArray()

    private fun legacyElements(domain: String, selector: String, excluded: Boolean = false) = ByteArrayOutputStream().apply {
        write("YZBABPEF\u0000\u0001\u0001".toByteArray())
        write(byteArrayOf(1, 0, 0, 0, 1, 1, if (excluded) 1 else 0))
        writeString(domain); writeString(selector)
    }.toByteArray()

    private fun ByteArrayOutputStream.writeString(value: String) {
        val bytes = value.toByteArray()
        write(bytes.size and 255); write((bytes.size ushr 8) and 255); write(bytes)
    }
}
