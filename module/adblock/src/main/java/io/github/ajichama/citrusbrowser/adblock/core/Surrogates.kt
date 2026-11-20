/*
 * Copyright (C) 2026 CitrusBrowser contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package io.github.ajichama.citrusbrowser.adblock.core

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * 独自に作った代替スクリプト（assets/adblock/surrogates/）。各ファイルの冒頭に、根拠にした公開文書を記録している。
 * 遮断したフィルタの contentType の番号（SURROGATE_MASK）から、返すスクリプトを決める。
 */
internal object Surrogates {
    private val cache = ConcurrentHashMap<String, ByteArray>()

    fun fileName(contentType: Int): String? = when (contentType and ContentRequest.SURROGATE_MASK) {
        ContentRequest.SURROGATE_GOOGLE_IMA -> "google-ima.js"
        else -> null
    }

    /** 対応する代替スクリプトの中身。種類が代替スクリプトでない、または番号が未知なら null。 */
    fun script(context: Context, contentType: Int): ByteArray? {
        if (contentType and ContentRequest.REDIRECT_MASK != ContentRequest.REDIRECT_SURROGATE) return null
        val name = fileName(contentType) ?: return null
        return cache.getOrPut(name) { context.assets.open("adblock/surrogates/$name").use { it.readBytes() } }
    }
}
