package io.github.ajichama.citrusbrowser.core.utility.utils

import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserIntentMigrationTest {
    private val oldRoot = LEGACY_BROWSER_NAMESPACE
    private val newRoot = "io.github.ajichama.citrusbrowser"

    @Test
    fun internalComponentActionAndTypedExtraKeysAreMigrated() {
        val source = "intent:#Intent;action=$oldRoot.action.newTab;component=$oldRoot/$oldRoot.browser.BrowserActivity;" +
            "B.$oldRoot.extra.fullscreen=true;i.$oldRoot.extra.orientation=1;end"
        val result = migrateLegacyBrowserIntentUri(source)
        assertEquals("$newRoot/$newRoot.browser.BrowserActivity", field(result, "component"))
        assertEquals("$newRoot.action.newTab", field(result, "action"))
        assertTrue(result.contains("B.$newRoot.extra.fullscreen=true"))
        assertTrue(result.contains("i.$newRoot.extra.orientation=1"))
    }

    @Test
    fun alreadyRebrandedPackageCanKeepAnOldComponentClass() {
        val result = migrateLegacyBrowserIntentUri("intent:#Intent;component=$newRoot/$oldRoot.browser.BrowserActivity;end")
        assertEquals("$newRoot/$newRoot.browser.BrowserActivity", field(result, "component"))
    }

    @Test
    fun encodedComponentAndExtraKeyAreDecodedAsIdentifiers() {
        val source = "intent:#Intent;component=jp%2Ehazuki%2Eyuzubrowser%2Edebug%2F$oldRoot.browser.BrowserActivity;" +
            "S.jp%2Ehazuki%2Eyuzubrowser%2Eextra%2Eurl=a%3Bb%3Dc%2Bvalue;end"
        val result = migrateLegacyBrowserIntentUri(source)
        assertEquals("$newRoot.debug/$newRoot.browser.BrowserActivity", field(result, "component"))
        assertTrue(result.contains("S.$newRoot.extra.url=a%3Bb%3Dc%2Bvalue"))
    }

    @Test
    fun relativeClassAndPackageOnlyTargetsAreSupported() {
        val relative = migrateLegacyBrowserIntentUri("intent:#Intent;component=$oldRoot/.browser.BrowserActivity;end")
        assertEquals("$newRoot/.browser.BrowserActivity", field(relative, "component"))
        val packageOnly = migrateLegacyBrowserIntentUri("intent:#Intent;package=$oldRoot.debug;action=$oldRoot.action.default;end")
        assertEquals("$newRoot.debug", field(packageOnly, "package"))
        assertEquals("$newRoot.action.default", field(packageOnly, "action"))
    }

    @Test
    fun dataTextAndExtraValuesAreNotRewritten() {
        val data = "intent://example.com/$oldRoot?q=yuzu%3Aunchanged"
        val value = "$oldRoot%3Bcomponent%3D$oldRoot%2FUserText%2Bkeep"
        val result = migrateLegacyBrowserIntentUri("$data#Intent;action=$oldRoot.action.default;S.$oldRoot.extra.url=$value;end")
        assertTrue(result.startsWith("$data#Intent;"))
        assertTrue(result.contains("S.$newRoot.extra.url=$value;"))
    }

    @Test
    fun explicitThirdPartyTargetRemainsUnchanged() {
        val source = "intent:#Intent;component=other.app/.Activity;action=$oldRoot.action.default;S.$oldRoot.extra.url=$oldRoot;end"
        assertEquals(source, migrateLegacyBrowserIntentUri(source))
    }

    @Test
    fun arbitraryExtraKeysAndAlreadyMigratedUrisAreUnchanged() {
        val source = "intent:#Intent;action=$newRoot.action.default;component=$newRoot/$newRoot.browser.BrowserActivity;" +
            "S.$oldRoot.userNote=$oldRoot;end"
        assertEquals(source, migrateLegacyBrowserIntentUri(source))
        val ordinary = "https://example.com/$oldRoot#component=$oldRoot"
        assertEquals(ordinary, migrateLegacyBrowserIntentUri(ordinary))
    }

    @Test
    fun selectorAndOuterIntentAreMigratedIndependently() {
        val source = "intent:#Intent;component=other.app/.Activity;action=$oldRoot.action.default;SEL;" +
            "component=$oldRoot/.browser.BrowserActivity;action=$oldRoot.action.newTab;end"
        val result = migrateLegacyBrowserIntentUri(source)
        assertTrue(result.contains("component=other.app/.Activity;action=$oldRoot.action.default;SEL;"))
        assertTrue(result.contains("action=$newRoot.action.newTab;end"))
    }

    private fun field(source: String, name: String): String = URLDecoder.decode(
        source.substringAfter("#Intent;").split(';').first { it.startsWith("$name=") }.substringAfter('='),
        "UTF-8",
    )
}
