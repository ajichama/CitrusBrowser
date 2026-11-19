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

package io.github.ajichama.citrusbrowser.adblock.core

import android.content.Context
import android.webkit.JavascriptInterface
import java.security.SecureRandom

/**
 * scriptlet を各フレームの文書開始時に実行するための固定スクリプトと、適用する scriptlet を返す同期ブリッジ。
 *
 * 固定スクリプト（assets/adblock/scriptlets.js）は全フレームで同じ内容を使い、そのフレームの URL を
 * ブリッジへ渡して、適用する scriptlet を Kotlin 側のドメイン照合（要素フィルタと共通）で決める。
 * ブリッジ名と照合用の鍵はプロセスごとに乱数で決め、固定スクリプトは取得後にブリッジをページから外す。
 */
object ScriptletInjection {
    private const val ASSET = "adblock/scriptlets.js"
    private val random = SecureRandom()

    /** ページ側から推測しにくいブリッジ名（JavaScript の識別子として有効な形）。 */
    val interfaceName: String = "_c" + randomToken(12)
    private val secret: String = randomToken(24)

    @Volatile private var cachedScript: String? = null

    fun script(context: Context): String = cachedScript ?: context.assets.open(ASSET).bufferedReader().use { it.readText() }
        .replace("__CITRUS_SCRIPTLET_BRIDGE__", interfaceName)
        .replace("__CITRUS_SCRIPTLET_KEY__", secret)
        .also { cachedScript = it }

    /** [resolver] はフレームの URL から JSON 配列を返す。広告ブロックが無効・対象外なら "[]" を返す。 */
    fun createBridge(resolver: (String) -> String): Any = Bridge(secret, resolver)

    private class Bridge(private val secret: String, private val resolver: (String) -> String) {
        @JavascriptInterface
        fun get(key: String?, href: String?): String {
            if (key != secret || href.isNullOrEmpty() || href.length > MAX_URL_LENGTH) return EMPTY
            return try {
                resolver(href)
            } catch (_: Exception) {
                EMPTY
            }
        }
    }

    private fun randomToken(length: Int): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return String(CharArray(length) { chars[random.nextInt(chars.length)] })
    }

    const val EMPTY = "[]"
    private const val MAX_URL_LENGTH = 8192
}
