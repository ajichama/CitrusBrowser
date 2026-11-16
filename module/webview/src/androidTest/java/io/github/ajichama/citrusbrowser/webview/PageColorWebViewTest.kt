package io.github.ajichama.citrusbrowser.webview

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import io.github.ajichama.citrusbrowser.core.utility.utils.PageColorMode
import io.github.ajichama.citrusbrowser.ui.settings.AppPrefs
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** 実WebViewでの配色・DOM保持を検証する。APKのコンパイル成功と端末での実行成功は別に記録する。 */
@RunWith(AndroidJUnit4::class)
class PageColorWebViewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<PageColorTestActivity>
    private lateinit var activity: PageColorTestActivity
    private lateinit var web: NormalWebView
    private val pageLoads = AtomicInteger()

    @Before fun launch() {
        scenario = ActivityScenario.launch(PageColorTestActivity::class.java)
        scenario.onActivity { activity = it }
    }

    @After fun close() {
        if (::web.isInitialized) instrumentation.runOnMainSync {
            activity.container.removeView(web)
            web.destroy()
        }
        if (::scenario.isInitialized) scenario.close()
    }

    private fun create(darkHost: Boolean = false) = instrumentation.runOnMainSync {
        activity.setTheme(if (darkHost) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        web = NormalWebView(activity)
        activity.container.addView(web, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        web.settings.javaScriptEnabled = true
    }

    private fun load(html: String) {
        val loaded = CountDownLatch(1)
        instrumentation.runOnMainSync {
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    pageLoads.incrementAndGet()
                    loaded.countDown()
                }
            }
            web.loadDataWithBaseURL("https://page-color.example/", html, "text/html", "UTF-8", null)
        }
        assertTrue("テストページの読込が完了しません", loaded.await(10, TimeUnit.SECONDS))
    }

    private fun evaluate(script: String): String {
        val done = CountDownLatch(1)
        val result = AtomicReference<String>()
        instrumentation.runOnMainSync { web.evaluateJavascript(script) { result.set(it); done.countDown() } }
        assertTrue("JavaScriptの結果が返りません", done.await(5, TimeUnit.SECONDS))
        return result.get()
    }

    private fun awaitTrue(script: String) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        var result: String
        do {
            result = evaluate(script)
            if (result == "true") return
            Thread.sleep(40)
        } while (SystemClock.elapsedRealtime() < deadline)
        assertEquals("配色反映を確認できません: $script", "true", result)
    }

    private fun mode(value: Int) = instrumentation.runOnMainSync { web.webSettings.webTheme = value }

    private fun isLight(context: Context): Boolean {
        val attributes = context.obtainStyledAttributes(intArrayOf(android.R.attr.isLightTheme))
        return try { attributes.getBoolean(0, true) } finally { attributes.recycle() }
    }

    @Test fun sitePaletteChangesInPlaceWithoutLosingInputOrReloading() {
        create()
        load(AUTHOR_COLORS)
        awaitTrue("!matchMedia('(prefers-color-scheme: dark)').matches")
        evaluate("document.querySelector('input').value='入力を保持'; window.pageMarker=42")
        val loads = pageLoads.get()
        mode(PageColorMode.SITE_DARK)
        awaitTrue("matchMedia('(prefers-color-scheme: dark)').matches && getComputedStyle(document.body).backgroundColor==='rgb(17, 34, 51)'")
        mode(PageColorMode.AUTO_DARK)
        awaitTrue("getComputedStyle(document.body).backgroundColor==='rgb(17, 34, 51)'")
        mode(PageColorMode.LIGHT)
        awaitTrue("!matchMedia('(prefers-color-scheme: dark)').matches && getComputedStyle(document.body).backgroundColor==='rgb(255, 255, 255)'")
        assertEquals("true", evaluate("window.pageMarker===42 && document.querySelector('input').value==='入力を保持'"))
        assertEquals(loads, pageLoads.get())
        instrumentation.runOnMainSync { assertTrue(isLight(activity)) }
    }

    @Test fun lightPagesAreIndependentOfDarkActivityAndKeepActivityContextChain() {
        create(darkHost = true)
        mode(PageColorMode.LIGHT)
        load(AUTHOR_COLORS)
        awaitTrue("!matchMedia('(prefers-color-scheme: dark)').matches")
        instrumentation.runOnMainSync {
            assertFalse(isLight(activity))
            assertTrue(isLight(web.context))
            var current: Context = web.context
            repeat(20) { if (current is ContextWrapper && current !is Activity) current = (current as ContextWrapper).baseContext }
            assertSame(activity, current)
            web.dispatchConfigurationChanged(Configuration(activity.resources.configuration))
            assertTrue(isLight(web.context))
        }
        awaitTrue("!matchMedia('(prefers-color-scheme: dark)').matches")
    }

    @Test fun copiedSettingsAndResetUseCanonicalModeAndIndependentTheme() {
        create()
        mode(3)
        instrumentation.runOnMainSync {
            assertEquals(PageColorMode.AUTO_DARK, web.webSettings.webTheme)
            val copy = NormalWebView(activity)
            try {
                copy.webSettings.webTheme = web.webSettings.webTheme
                assertEquals(PageColorMode.AUTO_DARK, copy.webSettings.webTheme)
                assertFalse(isLight(copy.context))
                val saved = copy.webSettings.webTheme
                copy.webSettings.webTheme = PageColorMode.LIGHT
                assertTrue(isLight(copy.context))
                copy.webSettings.webTheme = saved
                assertFalse(isLight(copy.context))
                assertTrue(isLight(activity))
                if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                    assertTrue(WebSettingsCompat.isAlgorithmicDarkeningAllowed(copy.settings))
                }
            } finally { copy.destroy() }
        }
    }

    @Test fun legacyGlobalValueMigratesOnlyThePageColorKey() {
        val prefs = AppPrefs.getPreference(instrumentation.targetContext)
        val hadValue = prefs.contains("web_theme")
        val original = prefs.getInt("web_theme", 0)
        val sentinel = "page_color_test_unrelated"
        try {
            prefs.edit().putInt("web_theme", 3).putString(sentinel, "保持").commit()
            AppPrefs.load(instrumentation.targetContext)
            assertEquals(PageColorMode.AUTO_DARK, AppPrefs.webTheme.get().toInt())
            assertEquals(PageColorMode.AUTO_DARK, prefs.getInt("web_theme", -1))
            assertEquals("保持", prefs.getString(sentinel, null))
        } finally {
            val edit = prefs.edit().remove(sentinel)
            if (hadValue) edit.putInt("web_theme", original) else edit.remove("web_theme")
            edit.commit()
            AppPrefs.load(instrumentation.targetContext)
        }
    }

    @Test fun algorithmDarkensPlainPageOnlyWhenAllowed() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        create()
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING))
        mode(PageColorMode.SITE_DARK)
        load("<html><body style='margin:0;background:white;height:100vh'>plain page</body></html>")
        awaitLuminance(dark = false)
        mode(PageColorMode.AUTO_DARK)
        awaitLuminance(dark = true)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun awaitLuminance(dark: Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        var luminance = -1.0
        do {
            val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            val copied = CountDownLatch(1)
            val status = AtomicInteger(-1)
            instrumentation.runOnMainSync {
                web.postOnAnimation {
                    val location = IntArray(2)
                    web.getLocationInWindow(location)
                    val x = location[0] + web.width / 2
                    val y = location[1] + web.height / 2
                    PixelCopy.request(activity.window, Rect(x, y, x + 1, y + 1), bitmap,
                        { result -> status.set(result); copied.countDown() }, Handler(Looper.getMainLooper()))
                }
            }
            try {
                assertTrue("WebViewの描画を取得できません", copied.await(5, TimeUnit.SECONDS))
                if (status.get() == PixelCopy.SUCCESS) {
                    luminance = ColorUtils.calculateLuminance(bitmap.getPixel(0, 0))
                    if (if (dark) luminance < 0.4 else luminance > 0.8) return
                }
            } finally { bitmap.recycle() }
            Thread.sleep(40)
        } while (SystemClock.elapsedRealtime() < deadline)
        fail("自動暗色化の描画を確認できません: dark=$dark luminance=$luminance")
    }

    companion object {
        private const val AUTHOR_COLORS = """<html><head><meta name="color-scheme" content="light dark"><style>
            body{background:rgb(255,255,255);color:black} @media(prefers-color-scheme:dark){body{background:rgb(17,34,51);color:white}}
            </style></head><body><input aria-label="保持する入力"></body></html>"""
    }
}
