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

/**
 * `##+js(名前, 引数...)` の解析と保存形式。
 *
 * uBO と名前・引数だけを合わせた独自実装（Apache-2.0）。仕様は uBO wiki の公開文書
 * （Resources-Library と Static-filter-syntax の Scriptlet injection 節）だけを参照し、
 * uBO の scriptlet のソースは参照していない。
 */
object Scriptlets {
    /** 要素フィルタの保存領域に同居させるための接頭辞。CSS セレクタとしては不正な文字列にする。 */
    const val STORED_PREFIX = "+js\u001F"
    private const val SEPARATOR = '\u001F'

    /** 別名から正式名への対応。ここにない名前の行は、行ごと破棄する。 */
    private val names: Map<String, String> = buildMap {
        fun add(canonical: String, vararg aliases: String) {
            put(canonical, canonical)
            aliases.forEach { put(it, canonical) }
        }
        add("set-constant", "set")
        add("abort-on-property-read", "aopr")
        add("abort-on-property-write", "aopw")
        add("abort-current-script", "acs", "abort-current-inline-script", "acis")
        add("prevent-setTimeout", "no-setTimeout-if", "nostif", "setTimeout-defuser")
        add("prevent-setInterval", "no-setInterval-if", "nosiif", "setInterval-defuser")
        add("remove-node-text", "rmnt")
        add("set-local-storage-item")
        add("set-session-storage-item")
        add("addEventListener-defuser", "aeld", "prevent-addEventListener")
        // 第2弾
        add("prevent-window-open", "nowoif", "no-window-open-if", "window.open-defuser")
        add("prevent-fetch", "no-fetch-if")
        add("prevent-xhr", "no-xhr-if")
        add("adjust-setInterval", "nano-sib", "nano-setInterval-booster")
        add("adjust-setTimeout", "nano-stb", "nano-setTimeout-booster")
        add("prevent-requestAnimationFrame", "no-requestAnimationFrame-if", "norafif")
        add("abort-on-stack-trace", "aost")
        add("noeval-if", "prevent-eval-if")
        add("noeval")
        add("json-prune")
        add("remove-attr", "ra")
        add("remove-class", "rc")
        add("nowebrtc")
        add("set-cookie")
        add("remove-cookie", "cookie-remover")
        add("window.name-defuser")
        add("disable-newtab-links")
        add("href-sanitizer")
        // 第3弾
        add("json-prune-fetch-response")
        add("json-prune-xhr-response")
        add("json-edit")
        add("json-edit-fetch-response")
        add("json-edit-xhr-response")
    }

    /**
     * trusted の scriptlet。wiki の規定どおり、信頼できる出典（uBO 自身のリストと本体同梱のリスト）からだけ受け付ける。
     * rpnt・replace-node-text は wiki で trusted-replace-node-text の別名とされている。
     */
    private val trustedNames: Map<String, String> = buildMap {
        fun add(canonical: String, vararg aliases: String) {
            put(canonical, canonical)
            aliases.forEach { put(it, canonical) }
        }
        add("trusted-replace-node-text", "trusted-rpnt", "replace-node-text", "rpnt")
        add("trusted-set-local-storage-item")
        add("trusted-set-session-storage-item")
        add("trusted-replace-argument")
        add("trusted-json-edit")
        add("trusted-json-edit-fetch-response")
        add("trusted-json-edit-xhr-response")
    }

    private val trustedPrefixes = listOf(
        "https://ublockorigin.github.io/uAssets/",
        "https://ublockorigin.pages.dev/",
        // 任意のコミットや PR の参照を通さないよう、ブランチを固定する。
        "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/",
        "citrus://adblock/filter",
    )

    /** 購読の URL が、trusted の scriptlet を許す出典か。利用者が追加した任意のリストは含めない。 */
    fun isTrustedSource(url: String?): Boolean = url != null && trustedPrefixes.any { url.startsWith(it) }

    fun isScriptletBody(body: String) = body.trimStart().startsWith("+js(")

    /**
     * `+js(...)` の中身を [正式名, 引数...] に分解する。`+js()` は空のリスト（例外で全無効化に使う）。
     * 構文が壊れている場合と、未対応の名前は null。
     */
    fun parse(body: String, trusted: Boolean = false): List<String>? {
        val trimmed = body.trim()
        if (!trimmed.startsWith("+js(") || !trimmed.endsWith(")")) return null
        val inner = trimmed.substring(4, trimmed.length - 1)
        if (inner.isBlank()) return emptyList()
        val args = splitArguments(inner) ?: return null
        if (args.any { SEPARATOR in it }) return null
        val name = args[0].removeSuffix(".js")
        val canonical = names[name] ?: (if (trusted) trustedNames[name] else null) ?: return null
        return listOf(canonical) + args.drop(1)
    }

    fun encode(call: List<String>): String = STORED_PREFIX + call.joinToString(SEPARATOR.toString())

    /** procedural の要素フィルタ。scriptlet と同じ経路で、文書開始時の固定スクリプトへ渡す。 */
    const val PROCEDURAL_PREFIX = "+pf\u001F"
    const val PROCEDURAL_CALL = "#procedural"

    fun encodeProcedural(body: String): String = PROCEDURAL_PREFIX + body

    /** scriptlet・procedural のどちらか（CSS の注入には含めず、ブリッジで渡すもの）。 */
    fun isStored(selector: String) = selector.startsWith(STORED_PREFIX) || selector.startsWith(PROCEDURAL_PREFIX)

    fun decode(selector: String): List<String> {
        if (selector.startsWith(PROCEDURAL_PREFIX)) return listOf(PROCEDURAL_CALL, selector.removePrefix(PROCEDURAL_PREFIX))
        return selector.removePrefix(STORED_PREFIX).let { if (it.isEmpty()) emptyList() else it.split(SEPARATOR) }
    }

    /** 適用する呼び出しの一覧を、注入スクリプトへ渡す JSON 配列にする。 */
    fun toJson(calls: List<List<String>>): String = buildString {
        append('[')
        calls.forEachIndexed { index, call ->
            if (index > 0) append(',')
            append('[')
            call.forEachIndexed { i, value ->
                if (i > 0) append(',')
                appendJsonString(value)
            }
            append(']')
        }
        append(']')
    }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        value.forEach { c ->
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c < ' ' || c == ' ' || c == ' ' || c == '<' ->
                    append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> append(c)
            }
        }
        append('"')
    }

    /**
     * カンマで区切り、前後の空白を除く。`\,` はカンマそのもの。それ以外のバックスラッシュは正規表現のために残す。
     * 引用符は wiki の規定（quotes will be taken literally）どおり、文字として扱う。
     */
    private fun splitArguments(inner: String): List<String>? {
        val args = mutableListOf<String>()
        val value = StringBuilder()
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            when {
                c == '\\' && inner.getOrNull(i + 1) == ',' -> { value.append(','); i += 2; continue }
                c == ',' -> { args += value.toString().trim(); value.setLength(0) }
                else -> value.append(c)
            }
            i++
        }
        args += value.toString().trim()
        return args.takeIf { it[0].isNotEmpty() }
    }
}
