package io.github.ajichama.citrusbrowser.adblock.filter.abp

import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AbpFilterPreprocessorTest {
    private val root = "https://filters.example/lists/main.txt"

    @Test
    fun nestedRelativeIncludesAreExpandedInPlaceWithoutFragmentMetadata() = runBlocking {
        val requested = mutableListOf<String>()
        val processor = AbpFilterPreprocessor({ target, _ ->
            requested += target.toString()
            val text = if (target.encodedPath.endsWith("first.txt")) {
                "! Title: 断片\n||first.example^\n!#include nested/second.txt"
            } else "||second.example^"
            AbpFilterPreprocessor.Resource(target, text)
        })
        val result = processor.process("! Title: 親\n||before.example^\n!#include first.txt\n||after.example^", root)
        assertEquals(listOf("https://filters.example/lists/first.txt", "https://filters.example/lists/nested/second.txt"), requested)
        assertEquals(listOf("! Title: 親", "||before.example^", "||first.example^", "||second.example^", "||after.example^"), result.text.lines().filter { it.isNotEmpty() })
        assertTrue(result.hasIncludes)
    }

    @Test
    fun falseAndUnknownBranchesNeverFetchOrApplyEvenWhenNegatedOrInElse() = runBlocking {
        val processor = noFetch()
        val result = processor.process("""
            !#if false
            !#include blocked.txt
            ||false.example^
            !#else
            ||else.example^
            !#endif
            !#if !future_environment
            !#include unknown.txt
            ||unknown.example^
            !#else
            ||unknown-else.example^
            !#endif
        """.trimIndent(), root)
        assertEquals("||else.example^", result.text.trim())
        assertFalse(result.hasIncludes)
    }

    @Test
    fun environmentAndNestedExpressionsReflectWebViewWithoutClaimingAnExtension() = runBlocking {
        val result = noFetch().process("""
            !#if env_chromium && (env_mobile || env_firefox)
            ||android.example^
            !#if ext_ublock
            ||ublock-only.example^
            !#else
            ||ordinary.example^
            !#endif
            !#endif
            !#if cap_html_filtering
            ||html-only.example^
            !#endif
        """.trimIndent(), root)
        assertEquals(listOf("||android.example^", "||ordinary.example^"), result.text.lines().filter { it.isNotEmpty() })
    }

    @Test
    fun absoluteAndEscapingIncludePathsAreRejectedBeforeFetching() = runBlocking {
        for (path in listOf("../outside.txt", "sub/../../outside.txt", "/absolute.txt", "//other.example/a.txt",
            "https://other.example/a.txt", "sub\\a.txt", "%2e%2e/a.txt", "%252e%252e/a.txt", "sub/%2fescape.txt",
            "a.txt?query", "a.txt#fragment", "./a.txt", "a//b.txt", "a b.txt")) {
            expectFailure { noFetch().process("!#include $path", root) }
        }
    }

    @Test
    fun cyclesIncludeCountDepthAndAggregateSizeHaveBounds() = runBlocking {
        val cycle = AbpFilterPreprocessor({ target, _ -> AbpFilterPreprocessor.Resource(target, "!#include main.txt") })
        expectFailure { cycle.process("!#include child.txt", root) }
        val count = AbpFilterPreprocessor({ target, _ -> AbpFilterPreprocessor.Resource(target, "||child.example^") }, maxIncludes = 1)
        expectFailure { count.process("!#include one.txt\n!#include two.txt", root) }
        val depth = AbpFilterPreprocessor({ target, _ -> AbpFilterPreprocessor.Resource(target, "!#include next/child.txt") }, maxDepth = 1)
        expectFailure { depth.process("!#include child.txt", root) }
        val size = AbpFilterPreprocessor({ target, _ -> AbpFilterPreprocessor.Resource(target, "a".repeat(50)) }, maxCharacters = 60)
        expectFailure { size.process("!#include child.txt", root) }
    }

    @Test
    fun unclosedAndUnbalancedConditionsCannotPublishPartialRules() = runBlocking {
        for (text in listOf("!#if true\n||partial.example^", "!#else\n||partial.example^", "!#endif",
            "!#if true\n!#else\n!#else\n!#endif")) {
            expectFailure { noFetch().process(text, root) }
        }
    }

    @Test
    fun nonHttpSourcesCannotLoadAnInclude() = runBlocking {
        for (url in listOf("file:///tmp/list.txt", "citrus://adblock/filter")) {
            expectFailure { noFetch().process("!#include child.txt", url) }
        }
    }

    @Test
    fun escapedRedirectTargetIsRejectedAndFetchingCanBeCancelled() = runBlocking {
        val escaped = AbpFilterPreprocessor({ _, _ -> AbpFilterPreprocessor.Resource("https://outside.example/a.txt".toHttpUrl(), "||bad.example^") })
        expectFailure { escaped.process("!#include child.txt", root) }
        val started = CompletableDeferred<Unit>()
        val processor = AbpFilterPreprocessor({ _, _ -> started.complete(Unit); awaitCancellation() })
        val job = launch { processor.process("!#include child.txt", root) }
        withTimeout(5000) { started.await() }
        withTimeout(5000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
    }

    @Test
    fun redirectBoundaryKeepsTheOriginAndDirectoryAndRejectsEncodedPaths() {
        val parent = root.toHttpUrl()
        assertTrue(AbpFilterPreprocessor.isAllowedTarget(parent, "https://filters.example/lists/nested/child.txt".toHttpUrl()))
        for (target in listOf("http://filters.example/lists/a.txt", "https://other.example/lists/a.txt",
            "https://filters.example:8443/lists/a.txt", "https://user@filters.example/lists/a.txt",
            "https://filters.example/lists-other/a.txt", "https://filters.example/outside.txt",
            "https://filters.example/lists/a%2fb.txt", "https://filters.example/lists/a.txt?x=1",
            "https://filters.example/lists/a.txt#fragment")) {
            assertFalse(target, AbpFilterPreprocessor.isAllowedTarget(parent, target.toHttpUrl()))
        }
    }

    private fun noFetch() = AbpFilterPreprocessor({ _, _ -> throw AssertionError("取得してはいけません。") })

    private suspend fun expectFailure(block: suspend () -> Unit) {
        try {
            block()
            fail("無効な前処理が成功しました。")
        } catch (_: IOException) {
            // 不完全な購読を公開しないための失敗。
        }
    }
}
