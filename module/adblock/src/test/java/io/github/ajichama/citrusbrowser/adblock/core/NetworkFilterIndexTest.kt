package io.github.ajichama.citrusbrowser.adblock.core

import android.net.Uri
import io.github.ajichama.citrusbrowser.adblock.filter.ContentFilter
import io.github.ajichama.citrusbrowser.adblock.filter.abp.AbpFilterDecoder
import io.github.ajichama.citrusbrowser.adblock.filter.unified.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.withSettings
import java.io.File

class NetworkFilterIndexTest {
    @Test fun partialWordsRemainCandidatesForBothBlockingAndExceptions() {
        val request = request("https://advertising.example/pixel")
        val block = decode("advert").blackList
        assertTrue(block.single().isMatch(request))
        assertNotNull(container(block)[request])
        val decoded = decode("||advertising.example^\n@@advert")
        assertTrue(decoded.whiteList.single().isMatch(request))
        assertNull(Blocker(FilterContainer(), container(decoded.whiteList), container(decoded.blackList)).isBlock(request))
    }

    @Test fun indexedAndDirectMatchingAgreeForBoundariesCaseAndUnknownTokens() {
        val rules = listOf("advert", "/advert", "advert/", "example.com", "||example.com", "||example.com^",
            "example^", "||example.com^/pixel", "|https://example.com/", "/pixel|", "foo_bar", "http", "banner*image")
        val urls = listOf("https://advertising.example/pixel", "https://example.com/advert/", "https://example.com/advertising",
            "https://example.com/superadvert/", "https://example.com/pixel", "https://sub.example.com/pixel",
            "https://notexample.com/foo_bar", "https://example.com/banner1image", "https://EXAMPLE.com/ADVERT/")
        for (rule in rules) {
            val filters = decode(rule).blackList
            assertTrue(rule, filters.isNotEmpty())
            val indexed = container(filters)
            for (url in urls) {
                val request = request(url)
                assertEquals("$rule / $url", filters.any { it.isMatch(request) }, indexed[request] != null)
            }
        }
    }

    @Test fun repeatedUrlTokensVisitEachBucketOnceWithoutChangingDecisions() {
        val filter = CountingFilter(ContainsFilter("/repeat/", ContentRequest.TYPE_IMAGE, null, -1))
        val filters = container(listOf(filter))
        assertNull(filters[request("https://repeat.example/repeat/repeat?repeat=1", ContentRequest.TYPE_SCRIPT)])
        // 種別不一致でも候補の比較は一度だけ。URL中の同じ語の数には比例しない。
        assertEquals(1, filter.calls)
    }

    @Test fun representativeDomainRulesStayIndexedAndFallbackCostIsVisible() {
        val filters = ArrayList<CountingFilter>()
        repeat(10_000) { i -> filters += CountingFilter(StartEndFilter("tracker$i.example", ContentRequest.TYPE_IMAGE, true, null, -1)) }
        repeat(100) { i -> filters += CountingFilter(ContainsFilter("substring$i", ContentRequest.TYPE_IMAGE, null, -1)) }
        val container = container(filters)
        assertEquals(100, filters.count { NetworkFilterTag.best(it).isEmpty() })
        val requests = List(100) { i -> request("https://outside.example/resource$i", ContentRequest.TYPE_SCRIPT) }
        val started = System.nanoTime()
        requests.forEach { assertNull(container[it]) }
        val elapsed = System.nanoTime() - started
        val candidates = filters.sumOf { it.calls }
        assertEquals(10_000, candidates)
        // 全件走査なら 1,010,000 比較となる条件。時間は環境依存なので合否に使わない。
        println("AdBlock synthetic baseline: rules=${filters.size}, requests=100, fallback=100, candidates=$candidates, linearCandidates=${filters.size * 100}, elapsedNs=$elapsed")
        // Docker の環境変数で明示したときだけ追加計測する。Mockito の準備は測定に含めない。
        if (System.getenv("CITRUS_ADBLOCK_BENCHMARK") == "1") {
            repeat(10) { requests.forEach { container[it] } }
            val samples = List(20) {
                val begin = System.nanoTime()
                requests.forEach { container[it] }
                System.nanoTime() - begin
            }.sorted()
            println("AdBlock warmed synthetic timing: requestsPerBatch=100, batches=20, medianNs=${samples[10]}, p95Ns=${samples[18]}")
        }
    }

    @Test fun bundledAssetMatchesDirectScanAndReportsItsActualFallbackBaseline() {
        // Gradleのmodule作業ディレクトリとIDEのroot作業ディレクトリの双方で同じmain assetを読む。
        val asset = listOf(File("src/main/assets/adblock/citrus_filter.txt"),
            File("module/adblock/src/main/assets/adblock/citrus_filter.txt")).firstOrNull(File::isFile)
            ?: error("内蔵フィルタのmain assetが見つかりません。")
        val decoded = asset.bufferedReader().use { AbpFilterDecoder().decode(it, null) }
        val activeLines = asset.readLines().count { line ->
            val trimmed = line.trim().removePrefix("\uFEFF").trim()
            trimmed.isNotEmpty() && !trimmed.startsWith('!') && !trimmed.startsWith(AbpFilterDecoder.HEADER)
        }
        assertEquals("内蔵の通常ルールを黙って脱落させない", activeLines, decoded.blackList.size)
        assertTrue(decoded.whiteList.isEmpty())
        val filters = decoded.blackList.map(::CountingFilter)
        val indexed = container(filters)
        val fallback = filters.count { NetworkFilterTag.best(it).isEmpty() }
        val groups = linkedMapOf<String, List<ContentRequest>>()
        fun prepared(url: String, thirdParty: Boolean = true, page: String = "https://page.example.test/"): ContentRequest =
            request(url, ContentRequest.TYPE_SCRIPT).copy(pageUrl = request(page).url, isThirdParty = thirdParty)

        // モック作成・デコード・索引構築は計測区間に含めない。外部通信は行わない。
        groups["unrelated"] = List(30) { prepared("https://neutral.example.test/content/item$it.txt") }
        groups["matching"] = listOf("https://ad-arata.s3.amazonaws.com/assets/pixel.png",
            "https://cdn.doubleclick.com/pixel.gif", "https://googleads.g.doubleclick.net/pagead/test",
            "https://ads.pubmatic.com/resource.js").map { prepared(it) }
        groups["partial-word"] = listOf("https://neutral.example.test/creative_adserver.js",
            "https://neutral.example.test/adimages-extra/pixel.png", "https://super4clvr.jp/content",
            "https://superadboost.example.test/resource", "https://analyzer42.fc2.com/test",
            "https://neutral.example.test/adpapri-123.js").map { prepared(it) }
        groups["first-party"] = listOf(prepared("https://neutral.example.test/creative_adserver.js", false),
            prepared("https://cdn.doubleclick.com/pixel.gif", false))
        groups["domain-exclusion"] = listOf(prepared("https://ads.pubmatic.com/resource.js", page = "https://hackintosh.zone/"))

        var totalIndexedCandidates = 0L
        var totalDirectCandidates = 0L
        var matched = 0
        var unmatched = 0
        groups.forEach { (group, requests) ->
            filters.forEach { it.calls = 0 }
            val actual = requests.map { indexed[it] != null }
            val indexedCandidates = filters.sumOf { it.calls.toLong() }
            filters.forEach { it.calls = 0 }
            val expected = requests.map { request -> filters.any { it.isMatch(request) } }
            val directCandidates = filters.sumOf { it.calls.toLong() }
            requests.indices.forEach { i ->
                assertEquals("$group / ${requests[i].url}", expected[i], actual[i])
            }
            matched += expected.count { it }
            unmatched += expected.count { !it }
            totalIndexedCandidates += indexedCandidates
            totalDirectCandidates += directCandidates
            println("AdBlock bundled corpus: group=$group, requests=${requests.size}, matched=${expected.count { it }}, indexedCandidates=$indexedCandidates, directCandidates=$directCandidates")
        }
        assertTrue("corpusに該当と非該当の両方を含める", matched > 0 && unmatched > 0)
        val requests = groups.values.flatten()
        fun directBatch() { requests.forEach { request -> filters.any { it.isMatch(request) } } }
        fun indexedBatch() { requests.forEach { indexed[it] } }
        repeat(5) { directBatch(); indexedBatch() }
        val directTimes = mutableListOf<Long>()
        val indexedTimes = mutableListOf<Long>()
        repeat(20) { sample ->
            // 常に片方を先に測らず、GC/JIT等の偏りを軽減する。時間値は合否に使わない。
            fun directSample() { val begin = System.nanoTime(); directBatch(); directTimes += System.nanoTime() - begin }
            fun indexedSample() { val begin = System.nanoTime(); indexedBatch(); indexedTimes += System.nanoTime() - begin }
            if (sample % 2 == 0) { directSample(); indexedSample() } else { indexedSample(); directSample() }
        }
        val directSorted = directTimes.sorted()
        val indexedSorted = indexedTimes.sorted()
        println("AdBlock bundled baseline: rules=${filters.size}, indexed=${filters.size - fallback}, fallback=$fallback, requests=${requests.size}, indexedCandidates=$totalIndexedCandidates, directCandidates=$totalDirectCandidates")
        // Mockitoの呼出処理も含むJVMバッチ時間。実通信の待ち時間や端末上の性能を示すものではない。
        println("AdBlock bundled timing: requestsPerBatch=${requests.size}, batches=20, indexedP50Ns=${indexedSorted[9]}, indexedP95Ns=${indexedSorted[18]}, directP50Ns=${directSorted[9]}, directP95Ns=${directSorted[18]}")
    }

    private class CountingFilter(private val delegate: ContentFilter) : ContentFilter by delegate {
        var calls = 0
        override var next: ContentFilter? = null
        override fun isMatch(request: ContentRequest): Boolean {
            calls++
            return delegate.isMatch(request)
        }
    }
    private fun decode(text: String) = AbpFilterDecoder().decode(text.reader().buffered(), null)
    private fun container(filters: List<ContentFilter>) = FilterContainer().also { container -> filters.forEach(container::plusAssign) }
    private fun request(value: String, type: Int = ContentRequest.TYPE_IMAGE): ContentRequest {
        val parsed = java.net.URI(value)
        // 大量の比較でMockitoの呼出履歴を蓄積させない。比較回数はCountingFilterで計測する。
        val uri = mock(Uri::class.java, withSettings().stubOnly()).also {
            whenever(it.toString()).thenReturn(value)
            whenever(it.host).thenReturn(parsed.host)
            whenever(it.schemeSpecificPart).thenReturn(parsed.rawSchemeSpecificPart)
        }
        return ContentRequest(uri, uri, type, false)
    }
}
