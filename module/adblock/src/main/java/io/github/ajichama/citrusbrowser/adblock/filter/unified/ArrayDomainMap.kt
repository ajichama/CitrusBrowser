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

package io.github.ajichama.citrusbrowser.adblock.filter.unified

import androidx.collection.SimpleArrayMap
import java.util.Locale

class ArrayDomainMap(size: Int) : SimpleArrayMap<String, Boolean>(size), DomainMap {

    override val size: Int
        get() = size()

    override fun get(domain: String): Boolean? {
        // 最も具体的なドメインから調べ、親ドメインの指定もサブドメインへ適用する。
        var candidate = domain.lowercase(Locale.ROOT).trimEnd('.')
        while (true) {
            val index = indexOfKey(candidate)
            if (index >= 0) return valueAt(index)
            val dot = candidate.indexOf('.')
            if (dot < 0) return null
            candidate = candidate.substring(dot + 1)
        }
    }

    operator fun set(domain: String, value: Boolean) {
        put(domain.lowercase(Locale.ROOT).trimEnd('.'), value)
    }

    override var include: Boolean = false


    override fun getKey(index: Int): String {
        return keyAt(index)
    }

    override fun getValue(index: Int): Boolean {
        return valueAt(index)
    }
}
