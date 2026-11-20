package io.github.ajichama.citrusbrowser.search

import android.content.ContextWrapper
import android.net.Uri
import android.view.LayoutInflater
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.textfield.TextInputLayout
import com.squareup.moshi.Moshi
import io.github.ajichama.citrusbrowser.search.model.provider.SearchSettings
import io.github.ajichama.citrusbrowser.search.model.provider.SearchUrl
import io.github.ajichama.citrusbrowser.search.repository.SearchDefaults
import io.github.ajichama.citrusbrowser.search.repository.SearchUrlManager
import io.github.ajichama.citrusbrowser.ui.utils.SearchTemplates
import io.github.ajichama.citrusbrowser.ui.utils.makeUrlFromQuery
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SearchCompatibilityTest {
    @Test fun addressSearchEncodesInputButKeepsExplicitUrls() {
        val template = SearchTemplates.DEFAULT_GOOGLE_URL
        val input = "日本語 🍊 &safe=off + # % ? /"
        val uri = Uri.parse(input.makeUrlFromQuery(template, "%s"))
        assertEquals(input, uri.getQueryParameter("q"))
        assertEquals(setOf("q"), uri.queryParameterNames)
        for (url in listOf("https://example.org/?q=a+b&x=%23#part", "mailto:test@example.org", "citrus:home", "view-source:https://example.org/")) {
            assertEquals(url, url.makeUrlFromQuery(template, "%s"))
        }
        assertEquals("http://example.org", "example.org".makeUrlFromQuery(template, "%s").trimEnd('/'))
    }

    @Test fun providerMigrationRecoversAtomicBackupAndPreservesCustomItem() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "search-migration-${System.nanoTime()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getFilesDir() = directory }
        try {
            val moshi = Moshi.Builder().build()
            val custom = SearchUrl(8, "Custom", "https://example.org/?q=%s&safe=active", 7, false)
            val old = SearchSettings(8, 12, listOf(custom, SearchUrl(1, "Google", "https://www.google.co.jp/m?q=%s", 0xff2196f3.toInt(), true)))
            File(directory, "searchUrl.json.bak").writeText(moshi.adapter(SearchSettings::class.java).toJson(old))
            File(directory, "searchUrl.json").writeText("unfinished")
            SearchDefaults.migrateLegacyDefaults(isolated, moshi)
            val actual = SearchUrlManager(isolated, moshi).load()
            assertEquals(SearchDefaults.normalize(old), actual)
            assertEquals(custom, actual.items.first())
            assertFalse(File(directory, "searchUrl.json.bak").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun materialSearchFormRetainsTitleUrlFaviconAndColorControls() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, io.github.ajichama.citrusbrowser.ui.R.style.BrowserThemeDayNight)
            val form = LayoutInflater.from(context).inflate(R.layout.search_url_edit, null)
            val title = form.findViewById<TextInputLayout>(R.id.textInputLayout3)
            val url = form.findViewById<TextInputLayout>(R.id.textInputLayout4)
            title.editText!!.setText("My search")
            url.editText!!.setText("https://example.org/?q=%s")
            url.error = "入力を確認してください"
            assertEquals("My search", title.editText!!.text.toString())
            assertEquals("https://example.org/?q=%s", url.editText!!.text.toString())
            assertNotNull(url.error)
            assertNotNull(form.findViewById<android.view.View>(R.id.isUseFaviconCheckBox))
            assertNotNull(form.findViewById<android.view.View>(R.id.iconColorButton))
        }
    }

    @Test fun repositoryReportsIoFailureWithoutReplacingTheBlockingFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val blocker = File.createTempFile("search-save-blocker-", ".data", context.cacheDir)
        blocker.writeText("保持")
        val isolated = object : ContextWrapper(context) { override fun getFilesDir() = blocker }
        try {
            val result = SearchUrlManager(isolated, Moshi.Builder().build()).save(SearchSettings(0, 0, emptyList()))
            assertFalse(result)
            assertEquals("保持", blocker.readText())
        } finally { blocker.delete() }
    }
}
