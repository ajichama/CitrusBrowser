/*
 * Copyright (C) 2026 CitrusBrowser contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.ajichama.citrusbrowser.adblock.core

import okhttp3.HttpUrl
import okhttp3.internal.publicsuffix.PublicSuffix
import java.util.Locale

/** WebView が公開する情報だけを使い、不明な種別を複数の用途へ拡大しない。 */
internal object RequestClassification {
    fun contentType(mainFrame: Boolean, scheme: String?, path: String?, headers: Map<String, String>): Int {
        if (mainFrame) return ContentRequest.TYPE_DOCUMENT
        if (scheme.equals("ws", true) || scheme.equals("wss", true)) return ContentRequest.TYPE_WEB_SOCKET
        fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.trim()
        val destination = header("Sec-Fetch-Dest")?.lowercase(Locale.ROOT)
        val explicit = when (destination) {
            "document", "frame", "iframe" -> ContentRequest.TYPE_SUB_DOCUMENT
            "script", "worker", "sharedworker", "serviceworker", "audioworklet", "paintworklet" -> ContentRequest.TYPE_SCRIPT
            "style" -> ContentRequest.TYPE_STYLE_SHEET
            "image" -> ContentRequest.TYPE_IMAGE
            "font" -> ContentRequest.TYPE_FONT
            "audio", "video" -> ContentRequest.TYPE_MEDIA
            else -> null
        }
        if (explicit != null) return explicit
        if (header("X-Requested-With").equals("XMLHttpRequest", true)) return ContentRequest.TYPE_XHR
        // empty は fetch/XHR 以外も取り得る。明示用途のない通信を拡張子で script 等へ誤分類しない。
        if (destination in setOf("empty", "object", "embed", "track", "manifest", "report", "xslt")) return ContentRequest.TYPE_OTHER

        val extension = path?.substringAfterLast('/')?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)
        val fromExtension = when (extension) {
            "js", "mjs", "cjs" -> ContentRequest.TYPE_SCRIPT
            "css" -> ContentRequest.TYPE_STYLE_SHEET
            "otf", "ttf", "ttc", "woff", "woff2" -> ContentRequest.TYPE_FONT
            "avif", "bmp", "gif", "ico", "jpeg", "jpg", "png", "svg", "webp" -> ContentRequest.TYPE_IMAGE
            "aac", "flac", "m4a", "mp3", "oga", "ogg", "opus", "wav", "weba" -> ContentRequest.TYPE_MEDIA
            "avi", "m4v", "mkv", "mov", "mp4", "mpeg", "mpg", "ogv", "webm" -> ContentRequest.TYPE_MEDIA
            else -> null
        }
        if (fromExtension != null) return fromExtension
        // Accept は応答候補であり用途そのものではない。同じ用途に絞れる場合だけ補助情報にする。
        val candidates = header("Accept")?.split(',')?.mapNotNull { entry ->
            val parts = entry.trim().lowercase(Locale.ROOT).split(';').map(String::trim)
            if (parts.drop(1).any { it.startsWith("q=") && it.substring(2).toDoubleOrNull() == 0.0 }) null
            else when (val mime = parts[0]) {
                "text/css" -> ContentRequest.TYPE_STYLE_SHEET
                "application/javascript", "application/x-javascript", "text/javascript" -> ContentRequest.TYPE_SCRIPT
                else -> when {
                    mime.startsWith("image/") -> ContentRequest.TYPE_IMAGE
                    mime.startsWith("audio/") || mime.startsWith("video/") -> ContentRequest.TYPE_MEDIA
                    mime.startsWith("font/") -> ContentRequest.TYPE_FONT
                    else -> ContentRequest.TYPE_OTHER
                }
            }
        }?.distinct().orEmpty()
        return candidates.singleOrNull() ?: ContentRequest.TYPE_OTHER
    }

    fun isThirdParty(requestHost: String?, pageHost: String?): Boolean {
        val request = canonicalHost(requestHost) ?: return true
        val page = canonicalHost(pageHost) ?: return true
        if (request == page) return false
        // IPv6 も DNS 問合せなしで識別する。異なる IP を PSL の null 同士で同一扱いしない。
        if (isIp(request) || isIp(page)) return true
        val suffix = PublicSuffix.get()
        return (suffix.getEffectiveTldPlusOne(request) ?: request) !=
            (suffix.getEffectiveTldPlusOne(page) ?: page)
    }

    private fun canonicalHost(host: String?): String? = host?.trim()?.trimEnd('.')?.takeIf { it.isNotEmpty() }?.let {
        runCatching { HttpUrl.Builder().scheme("http").host(it).build().host }.getOrNull()
    }

    private fun isIp(host: String) = ':' in host || host.all { it in '0'..'9' || it == '.' }
}
