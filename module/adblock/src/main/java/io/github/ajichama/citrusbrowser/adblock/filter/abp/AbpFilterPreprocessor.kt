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

package io.github.ajichama.citrusbrowser.adblock.filter.abp

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.BufferedReader
import java.io.IOException

/** 購読を展開してから構文解析へ渡す。取得・条件の不明確さを制約の脱落に変えない。 */
internal class AbpFilterPreprocessor(
    private val fetch: suspend (target: HttpUrl, parent: HttpUrl) -> Resource,
    private val maxCharacters: Int = MAX_CHARACTERS,
    private val maxIncludes: Int = 64,
    private val maxDepth: Int = 8,
) {
    data class Resource(val url: HttpUrl, val text: String)
    data class Result(val text: String, val hasIncludes: Boolean)
    private data class Branch(val parentEnabled: Boolean, val condition: Boolean?, var hasElse: Boolean = false)

    suspend fun process(text: String, sourceUrl: String): Result {
        val output = StringBuilder()
        val activeUrls = mutableSetOf<String>()
        var characters = 0L
        var includeCount = 0

        suspend fun expand(contents: String, base: HttpUrl?, depth: Int) {
            currentCoroutineContext().ensureActive()
            if (depth > maxDepth) throw IOException("フィルタの include が深さの上限を超えました。")
            characters += contents.length
            if (characters > maxCharacters) throw IOException("フィルタの総文字数が上限を超えました。")
            val key = base?.toString()
            if (key != null && !activeUrls.add(key)) throw IOException("フィルタの include が循環しています。")
            try {
                val branches = ArrayDeque<Branch>()
                var enabled = true
                contents.lineSequence().forEach { line ->
                    currentCoroutineContext().ensureActive()
                    val trimmed = line.trim().removePrefix("\uFEFF").trim()
                    val directive = DIRECTIVE.matchEntire(trimmed)
                    if (directive != null) {
                        val argument = directive.groupValues[2].trim()
                        when (directive.groupValues[1]) {
                            "if" -> {
                                if (branches.size >= 64) throw IOException("フィルタの条件が深さの上限を超えました。")
                                val condition = Condition(argument).evaluate()
                                branches.addLast(Branch(enabled, condition))
                                enabled = enabled && condition == true
                            }
                            "else" -> {
                                val branch = branches.lastOrNull() ?: throw IOException("対応する if がありません。")
                                if (argument.isNotEmpty() || branch.hasElse) throw IOException("フィルタの else が不正です。")
                                branch.hasElse = true
                                // 未知条件は否定しても不明のまま。else を無条件で有効にしない。
                                enabled = branch.parentEnabled && branch.condition == false
                            }
                            "endif" -> {
                                if (argument.isNotEmpty() || branches.isEmpty()) throw IOException("フィルタの endif が不正です。")
                                enabled = branches.removeLast().parentEnabled
                            }
                            "include" -> if (enabled) {
                                val parent = base ?: throw IOException("HTTP 購読以外の include は取得できません。")
                                if (++includeCount > maxIncludes) throw IOException("フィルタの include が件数の上限を超えました。")
                                if (depth >= maxDepth) throw IOException("フィルタの include が深さの上限を超えました。")
                                val target = resolve(parent, argument)
                                if (target.toString() in activeUrls) throw IOException("フィルタの include が循環しています。")
                                val resource = fetch(target, parent)
                                if (!isAllowedTarget(parent, resource.url)) throw IOException("include の取得先が許可範囲外です。")
                                expand(resource.text, resource.url, depth + 1)
                            }
                        }
                    } else if (enabled) {
                        // 購読の名前・更新日時は親に属する。断片のコメントで上書きしない。
                        if (depth == 0 || !trimmed.startsWith('!')) output.append(line).append('\n')
                    }
                }
                if (branches.isNotEmpty()) throw IOException("閉じられていないフィルタ条件があります。")
            } finally {
                if (key != null) activeUrls.remove(key)
            }
        }

        expand(text, sourceUrl.toHttpUrlOrNull(), 0)
        return Result(output.toString(), includeCount != 0)
    }

    private class Condition(private val expression: String) {
        private val tokens = if (expression.length <= 1024) TOKEN.findAll(expression).map { it.value }.toList() else emptyList()
        private var index = 0
        private var unknown = false

        fun evaluate(): Boolean? {
            if (expression.length > 1024 || tokens.size > 128 || tokens.joinToString("") != expression.filterNot { it.isWhitespace() }) return null
            return try {
                val result = or()
                if (index != tokens.size || unknown) null else result
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private fun or(): Boolean {
            var value = and()
            while (take("||")) {
                val right = and()
                value = value || right
            }
            return value
        }

        private fun and(): Boolean {
            var value = unary()
            while (take("&&")) {
                val right = unary()
                value = value && right
            }
            return value
        }

        private fun unary(): Boolean {
            if (take("!")) return !unary()
            if (take("(")) {
                val value = or()
                require(take(")"))
                return value
            }
            val token = tokens.getOrNull(index++) ?: throw IllegalArgumentException()
            return when (token) {
                "true", "env_mobile", "env_chromium" -> true
                "false", "env_firefox", "env_edge", "env_safari", "env_mv3",
                "ext_ublock", "ext_ubol", "ext_abp", "ext_devbuild", "cap_html_filtering", "cap_user_stylesheet",
                "adguard", "adguard_app_android", "adguard_app_ios", "adguard_app_mac", "adguard_app_windows",
                "adguard_ext_android_cb", "adguard_ext_chromium", "adguard_ext_edge", "adguard_ext_firefox",
                "adguard_ext_opera", "adguard_ext_safari" -> false
                else -> { unknown = true; false }
            }
        }

        private fun take(token: String): Boolean = if (tokens.getOrNull(index) == token) { index++; true } else false
    }

    companion object {
        const val MAX_CHARACTERS = 16 * 1024 * 1024
        private val DIRECTIVE = Regex("!#(if|else|endif|include)(?:\\s+(.*))?")
        private val TOKEN = Regex("[a-zA-Z_][a-zA-Z_0-9]*|&&|\\|\\||[!()]")

        private fun resolve(parent: HttpUrl, path: String): HttpUrl {
            // % も拒否し、復号や URL 正規化で相対パスの境界が変わる余地を残さない。
            if (path.isEmpty() || path.startsWith('/') || path.any { it.isWhitespace() || it in "\\%?#:@" } ||
                path.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
                throw IOException("include は同じディレクトリ配下の相対パスだけ指定できます。")
            }
            val target = parent.resolve(path) ?: throw IOException("include の URL が不正です。")
            if (!isAllowedTarget(parent, target)) throw IOException("include の取得先が許可範囲外です。")
            return target
        }

        fun isAllowedTarget(parent: HttpUrl, target: HttpUrl): Boolean =
            parent.scheme == target.scheme && parent.host == target.host && parent.port == target.port &&
                parent.username == target.username && parent.password == target.password &&
                target.fragment == null && target.query == null &&
                target.encodedPath.startsWith(parent.encodedPath.substringBeforeLast('/') + "/") &&
                '%' !in target.encodedPath && '\\' !in target.encodedPath

        suspend fun readBounded(reader: BufferedReader): String {
            val result = StringBuilder()
            val buffer = CharArray(8192)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = reader.read(buffer)
                if (count < 0) return result.toString()
                if (result.length + count > MAX_CHARACTERS) throw IOException("フィルタの文字数が上限を超えました。")
                result.append(buffer, 0, count)
            }
        }
    }
}
