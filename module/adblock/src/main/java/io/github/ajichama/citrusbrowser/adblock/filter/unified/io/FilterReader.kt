/*
 * Copyright (C) 2017-2019 Hazuki
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

package io.github.ajichama.citrusbrowser.adblock.filter.unified.io

import io.github.ajichama.citrusbrowser.adblock.filter.toInt
import io.github.ajichama.citrusbrowser.adblock.filter.toShortInt
import io.github.ajichama.citrusbrowser.adblock.filter.unified.*
import java.io.InputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException

class FilterReader(private val input: InputStream) {
    private val dataInput = DataInputStream(input)
    private var version = 0

    fun checkHeader(): Boolean {
        val bytes = ByteArray(FILTER_CACHE_HEADER.toByteArray().size)
        try { dataInput.readFully(bytes) } catch (_: EOFException) { return false }
        version = when {
            bytes.contentEquals(FILTER_CACHE_HEADER.toByteArray()) -> 2
            bytes.contentEquals(LEGACY_FILTER_CACHE_HEADER.toByteArray()) -> 1
            else -> 0
        }
        return version != 0
    }

    fun readAll() = sequence {
        if (version == 0) throw IOException("フィルタキャッシュのヘッダーを確認してください。")
        val intBuf = ByteArray(4)
        val shortBuf = ByteArray(2)
        input.read(intBuf)
        val size = intBuf.toInt()
        var patternBuffer = ByteArray(32)

        loop@ for (loop in 0 until size) {
            val type = input.read()
            if (type < 0) break

            val contentType = if (version >= 2) {
                dataInput.readFully(intBuf)
                intBuf.toInt()
            } else {
                dataInput.readFully(shortBuf)
                shortBuf.toShortInt()
            }

            val ignoreCase = when (input.read()) {
                0 -> false
                1 -> true
                else -> break@loop
            }

            val thirdParty = when (input.read()) {
                0 -> 0
                1 -> 1
                0xff -> -1
                else -> break@loop
            }

            val patternSize = input.readVariableInt(shortBuf, intBuf)
            if (patternSize == -1) break
            if (patternBuffer.size < patternSize) {
                patternBuffer = ByteArray(patternSize)
            }
            if (input.read(patternBuffer, 0, patternSize) != patternSize) break
            var pattern = String(patternBuffer, 0, patternSize)
            var actualType = type
            var ruleKey: String? = null
            var constraints: NetworkConstraints? = null
            if (type == FILTER_TYPE_NETWORK_RULE) {
                // 3欄（種類・識別子・パターン）または、追加条件を挟んだ4欄。パターンは NUL を含まない。
                val parts = pattern.split('\u0000')
                if (parts.size != 3 && parts.size != 4) throw IOException("フィルタの識別情報が壊れています。")
                actualType = parts[0].toIntOrNull() ?: throw IOException("フィルタの種類が不正です。")
                ruleKey = parts[1]
                if (parts.size == 4) {
                    constraints = NetworkConstraints.decode(parts[2]) ?: throw IOException("フィルタの追加条件が壊れています。")
                }
                pattern = parts.last()
            }

            val domainsSize = input.read()
            if (domainsSize == -1) break

            val domains = when (domainsSize) {
                0 -> null
                1 -> {
                    val containerInclude = when (input.read()) {
                        0 -> false
                        1 -> true
                        else -> break@loop
                    }
                    val textSize = input.readVariableInt(shortBuf, intBuf)
                    if (textSize == -1) break@loop
                    if (patternBuffer.size < textSize) {
                        patternBuffer = ByteArray(textSize)
                    }
                    if (input.read(patternBuffer, 0, textSize) != textSize) break@loop
                    val domain = String(patternBuffer, 0, textSize)
                    val include = when (input.read()) {
                        0 -> false
                        1 -> true
                        else -> break@loop
                    }

                    if (containerInclude != include) break@loop

                    SingleDomainMap(include, domain)
                }
                else -> {
                    val map = ArrayDomainMap(domainsSize)
                    map.include = when (input.read()) {
                        0 -> false
                        1 -> true
                        else -> break@loop
                    }
                    for (i in 0 until domainsSize) {
                        val textSize = input.readVariableInt(shortBuf, intBuf)
                        if (textSize == -1) break@loop
                        if (patternBuffer.size < textSize) {
                            patternBuffer = ByteArray(textSize)
                        }
                        if (input.read(patternBuffer, 0, textSize) != textSize) break@loop
                        val domain = String(patternBuffer, 0, textSize)
                        val include = when (input.read()) {
                            0 -> false
                            1 -> true
                            else -> break@loop
                        }
                        map[domain] = include
                    }
                    map
                }
            }

            val filter = when (actualType) {
                FILTER_TYPE_CONTAINS -> ContainsFilter(pattern, contentType, domains, thirdParty, ignoreCase)
                FILTER_TYPE_HOST -> HostFilter(pattern, contentType, ignoreCase, domains, thirdParty)
                FILTER_TYPE_CONTAINS_HOST -> ContainsHostFilter(pattern, contentType, ignoreCase, domains, thirdParty)
                FILTER_TYPE_START -> StartsWithFilter(pattern, contentType, ignoreCase, domains, thirdParty)
                FILTER_TYPE_END -> EndWithFilter(pattern, contentType, domains, thirdParty, ignoreCase)
                FILTER_TYPE_START_END -> StartEndFilter(pattern, contentType, ignoreCase, domains, thirdParty)
                FILTER_TYPE_RE2_REGEX -> Re2Filter(pattern, contentType, ignoreCase, domains, thirdParty)
                FILTER_TYPE_RE2_REGEX_HOST -> Re2HostFilter(pattern, contentType, ignoreCase, domains, thirdParty)
                FILTER_TYPE_JVM_REGEX -> RegexFilter(pattern, contentType, ignoreCase, domains, thirdParty)
                FILTER_TYPE_JVM_REGEX_HOST -> RegexHostFilter(pattern, contentType, ignoreCase, domains, thirdParty)
                FILTER_TYPE_PATTERN -> PatternMatchFilter(pattern, contentType, ignoreCase, domains, thirdParty)
                else -> break@loop
            }
            yield(if (ruleKey != null) NetworkRuleFilter(filter, ruleKey, 0, constraints) else filter)
        }
    }
}
