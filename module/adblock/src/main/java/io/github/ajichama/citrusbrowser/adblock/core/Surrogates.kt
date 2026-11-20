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
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * 独自に作った代替資源（assets/adblock/ 以下）。各ファイルの冒頭（MP4 は生成スクリプト）に、根拠にした公開文書を記録している。
 * 遮断したフィルタの contentType の番号（SURROGATE_MASK）から、返す資源を決める。
 */
internal object Surrogates {
    class Resource(val asset: String, val mimeType: String, val encoding: String?)

    private const val JS = "application/javascript"
    private const val XML = "text/xml"

    private val resources = mapOf(
        ContentRequest.SURROGATE_GOOGLE_IMA to Resource("adblock/surrogates/google-ima.js", JS, "UTF-8"),
        ContentRequest.SURROGATE_ADSBYGOOGLE to Resource("adblock/surrogates/googlesyndication-adsbygoogle.js", JS, "UTF-8"),
        ContentRequest.SURROGATE_FUCKADBLOCK to Resource("adblock/surrogates/fuckadblock.js", JS, "UTF-8"),
        ContentRequest.SURROGATE_VAST2 to Resource("adblock/surrogates/noop-vast2.xml", XML, "UTF-8"),
        ContentRequest.SURROGATE_VAST3 to Resource("adblock/surrogates/noop-vast3.xml", XML, "UTF-8"),
        ContentRequest.SURROGATE_VAST4 to Resource("adblock/surrogates/noop-vast4.xml", XML, "UTF-8"),
        ContentRequest.SURROGATE_VMAP1 to Resource("adblock/surrogates/noop-vmap1.xml", XML, "UTF-8"),
        // tools/adblock/make-noop-mp4.mjs で生成した、約1秒の無音の MP4。
        ContentRequest.SURROGATE_NOOP_MP4 to Resource("adblock/noop-1s.mp4", "video/mp4", null),
    )

    /** redirect の資源名（別名を含む）から、種類と番号を合わせた値。未知の名前なら null。 */
    fun redirectType(name: String): Int? = when (name) {
        "google-ima.js", "google-ima3" -> ContentRequest.SURROGATE_GOOGLE_IMA
        "googlesyndication_adsbygoogle.js", "googlesyndication.com/adsbygoogle.js" -> ContentRequest.SURROGATE_ADSBYGOOGLE
        "fuckadblock.js-3.2.0", "nofab.js" -> ContentRequest.SURROGATE_FUCKADBLOCK
        "noop-vast2.xml", "noopvast-2.0" -> ContentRequest.SURROGATE_VAST2
        "noop-vast3.xml", "noopvast-3.0" -> ContentRequest.SURROGATE_VAST3
        "noop-vast4.xml", "noopvast-4.0" -> ContentRequest.SURROGATE_VAST4
        "noop-vmap1.xml", "noopvmap-1.0" -> ContentRequest.SURROGATE_VMAP1
        "noop-1s.mp4", "noopmp4-1s" -> ContentRequest.SURROGATE_NOOP_MP4
        else -> null
    }?.let { ContentRequest.REDIRECT_SURROGATE or it }

    /** 対応する代替資源。種類が代替資源でない、または番号が未知なら null。 */
    fun resource(contentType: Int): Resource? {
        if (contentType and ContentRequest.REDIRECT_MASK != ContentRequest.REDIRECT_SURROGATE) return null
        return resources[contentType and ContentRequest.SURROGATE_MASK]
    }

    private val cache = ConcurrentHashMap<String, ByteArray>()

    fun bytes(context: Context, resource: Resource): ByteArray =
        cache.getOrPut(resource.asset) { context.assets.open(resource.asset).use { it.readBytes() } }

    fun response(context: Context, resource: Resource): WebResourceResponse =
        WebResourceResponse(resource.mimeType, resource.encoding, ByteArrayInputStream(bytes(context, resource)))
}

/**
 * 差し替えた応答を、要求元のオリジンから読めるようにする（別オリジンの fetch・XHR は、CORS のヘッダーがないと失敗する）。
 * 中身は独自の空資源・代替資源だけなので、読めても実際の別オリジンのデータは渡らない。資格情報つきの要求にも通るよう、オリジンをそのまま返す。
 */
internal fun WebResourceResponse.allowingOrigin(origin: String?): WebResourceResponse {
    if (origin.isNullOrEmpty()) return this
    responseHeaders = mapOf("Access-Control-Allow-Origin" to origin, "Access-Control-Allow-Credentials" to "true")
    return this
}

/** 要求の Origin ヘッダー、なければページの URL から求めた要求元のオリジン。求められなければ null。 */
internal fun requestOrigin(headers: Map<String, String>?, page: android.net.Uri?): String? {
    headers?.entries?.firstOrNull { it.key.equals("Origin", ignoreCase = true) }?.value?.let { return it }
    val scheme = page?.scheme ?: return null
    val host = page.host ?: return null
    if (!scheme.equals("http", ignoreCase = true) && !scheme.equals("https", ignoreCase = true)) return null
    return if (page.port == -1) "$scheme://$host" else "$scheme://$host:${page.port}"
}
