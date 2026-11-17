package io.github.ajichama.citrusbrowser.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.shape.MaterialShapeDrawable
import io.github.ajichama.citrusbrowser.ui.settings.AppPrefs
import io.github.ajichama.citrusbrowser.ui.utils.SearchTemplates
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MaterialThemeCompatibilityTest {
    @Test fun dayAndNightThemesResolveExistingPaletteAndMaterialShapes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
                val base = instrumentation.targetContext
                val config = Configuration(base.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
                }
                val context = ContextThemeWrapper(base.createConfigurationContext(config), R.style.BrowserThemeDayNight_Pref)
                val roles = context.obtainStyledAttributes(intArrayOf(
                    com.google.android.material.R.attr.colorSurface,
                    com.google.android.material.R.attr.colorOnSurface))
                try {
                    assertEquals(context.getColor(R.color.background), roles.getColor(0, 0))
                    assertEquals(context.getColor(R.color.primary_text), roles.getColor(1, 0))
                } finally {
                    roles.recycle()
                }
                val button = MaterialButton(context).apply { text = "確認" }
                assertTrue(button.shapeAppearanceModel.topLeftCornerSize.getCornerSize(android.graphics.RectF(0f, 0f, 200f, 64f)) > 0)
                val dialog = MaterialAlertDialogBuilder(context).setTitle("設定").setMessage("設定値は保持されます")
                    .setPositiveButton(android.R.string.ok, null).create()
                assertTrue(dialog.window!!.decorView.background is MaterialShapeDrawable || dialog.window!!.decorView.background is android.graphics.drawable.InsetDrawable)
                dialog.dismiss()
            }
        }
    }

    @Test fun globalDefaultMigrationWritesOnlyKnownSearchKeyAndKeepsCustomUrl() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "search-compatibility-${System.nanoTime()}"
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(ignored: String, mode: Int) = preferences
        }
        try {
            preferences.edit().putString("search_url", "https://www.google.co.jp/m?q=%s")
                .putString("custom-marker", "保持").putInt("web_theme", 0).commit()
            AppPrefs.load(isolated)
            assertEquals(SearchTemplates.DEFAULT_GOOGLE_URL, preferences.getString("search_url", null))
            assertEquals(setOf("search_url", "custom-marker", "web_theme"), preferences.all.keys)
            assertEquals("保持", preferences.getString("custom-marker", null))
            val custom = "https://www.google.co.jp/m?q=%s&safe=active"
            preferences.edit().putString("search_url", custom).commit()
            AppPrefs.load(isolated)
            assertEquals(custom, AppPrefs.search_url.get())
            assertEquals(custom, preferences.getString("search_url", null))
        } finally {
            preferences.edit().clear().commit()
            AppPrefs.load(context)
        }
    }
}
