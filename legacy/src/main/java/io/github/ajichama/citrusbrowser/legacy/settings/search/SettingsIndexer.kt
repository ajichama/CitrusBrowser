/*
 * Copyright (C) 2026 CitrusBrowser contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.ajichama.citrusbrowser.legacy.settings.search

import android.content.Context
import android.content.res.XmlResourceParser
import io.github.ajichama.citrusbrowser.legacy.R
import io.github.ajichama.citrusbrowser.legacy.settings.activity.MainSettingsFragment
import org.xmlpull.v1.XmlPullParser

/**
 * 設定の XML を読んで、検索の索引を作る（#25）。
 *
 * 項目を作らずに XML の属性だけを読むので、設定の値や画面には触れない。題名は資源から引くので、
 * 表示している言語のまま探せる。コードで消したり足したりする項目は、XML のとおりに扱う
 * （開いた画面に無ければ、スクロールせずに画面を開くだけになる）。
 */
class SettingsIndexer(private val context: Context) {

    fun build(): List<SettingsSearchEntry> {
        val topTitles = readTopTitles()
        val entries = mutableListOf<SettingsSearchEntry>()
        MainSettingsFragment.SCREENS.forEach { (topKey, xml) ->
            val topTitle = topTitles[topKey] ?: return@forEach
            // トップ画面の項目そのもの（例: 「UI 設定」）でも見つかるようにする。
            entries.add(SettingsSearchEntry(topTitle, null, emptyList(), topKey, emptyList(), null))
            parse(xml, topKey, listOf(topTitle), emptyList(), entries)
        }
        return entries
    }

    private fun readTopTitles(): Map<String, String> {
        val titles = mutableMapOf<String, String>()
        context.resources.getXml(R.xml.pref_main).use { parser ->
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG) continue
                val key = parser.getAttributeValue(ANDROID_NS, "key") ?: continue
                text(parser, "title")?.let { titles[key] = it }
            }
        }
        return titles
    }

    /** 画面・区分の入れ子を表す。[pathPart] は場所の表示に、[clickKey] は移動に使う。 */
    private class Frame(val pathPart: String?, val clickKey: String?)

    private fun parse(
        xml: Int,
        topKey: String,
        basePath: List<String>,
        baseClicks: List<String>,
        out: MutableList<SettingsSearchEntry>,
    ) {
        context.resources.getXml(xml).use { parser ->
            val stack = ArrayDeque<Frame>()
            var rootSeen = false
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        val title = text(parser, "title")
                        val key = parser.getAttributeValue(ANDROID_NS, "key")
                        val path = basePath + stack.mapNotNull { it.pathPart }
                        val clicks = baseClicks + stack.mapNotNull { it.clickKey }
                        if (!rootSeen) {
                            rootSeen = true
                            stack.addLast(Frame(null, null))
                            continue
                        }
                        when (parser.name) {
                            "PreferenceScreen" -> {
                                if (title != null) out.add(entry(parser, title, path, topKey, clicks, key))
                                // キーの無い入れ子の画面へは進めないので、空のキーにして移動をそこで止める。
                                stack.addLast(Frame(title, key ?: ""))
                            }
                            "PreferenceCategory" -> stack.addLast(Frame(title, null))
                            else -> {
                                if (title != null) out.add(entry(parser, title, path, topKey, clicks, key))
                                stack.addLast(Frame(null, null))
                                val child = key?.let { LINKS[it] }
                                if (child != null && title != null) parse(child, topKey, path + title, clicks + key, out)
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> stack.removeLastOrNull()
                }
            }
        }
    }

    private fun entry(
        parser: XmlResourceParser,
        title: String,
        path: List<String>,
        topKey: String,
        clicks: List<String>,
        key: String?,
    ): SettingsSearchEntry {
        // 「%s」などの値を差し込む説明は、そのままでは意味をなさないので使わない。
        val summary = text(parser, "summary")?.takeUnless { it.contains('%') }
        return SettingsSearchEntry(title, summary, path, topKey, clicks, key)
    }

    private fun text(parser: XmlResourceParser, name: String): String? {
        val resId = parser.getAttributeResourceValue(ANDROID_NS, name, 0)
        val value = if (resId != 0) context.getString(resId) else parser.getAttributeValue(ANDROID_NS, name)
        return value?.trim()?.takeIf { it.isNotEmpty() }
    }

    companion object {
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

        /** 項目を押すと、コードで別の画面を開くもの（UiSettingFragment）。キー → その画面の XML。 */
        private val LINKS = mapOf(
            "theme_management" to R.xml.pref_theme_managment,
            "reader_settings" to R.xml.pref_reader,
        )
    }
}
