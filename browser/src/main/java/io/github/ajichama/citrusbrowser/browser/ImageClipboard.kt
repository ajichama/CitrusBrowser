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
package io.github.ajichama.citrusbrowser.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.widget.Toast
import io.github.ajichama.citrusbrowser.core.utility.utils.ui
import io.github.ajichama.citrusbrowser.ui.BrowserApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * 長押しした画像を、画像としてクリップボードへコピーする。
 * 画像はアプリ専用の外部キャッシュ（Android/data/…/cache/clipboard）へ一時保存し、ファイル提供部分の content:// の URI で渡す。
 * ダウンロードフォルダには残さない。貼り付け先のアプリには、Android がクリップボード経由で読み取りの許可を与える。
 */
internal object ImageClipboard {
    private const val MAX_BYTES = 20L * 1024 * 1024

    fun copy(context: Context, client: OkHttpClient, url: String, userAgent: String?, referer: String?) = ui {
        val copied = withContext(Dispatchers.IO) {
            try {
                val image = load(client, url, userAgent, referer) ?: return@withContext false
                setClip(context, save(context, image) ?: return@withContext false)
                true
            } catch (e: IOException) {
                false
            } catch (e: IllegalArgumentException) {
                false
            }
        }
        // Android 13 以降は、コピーしたことを OS が表示する。
        if (!copied) {
            Toast.makeText(context, R.string.copy_image_failed, Toast.LENGTH_SHORT).show()
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, R.string.copy_image_done, Toast.LENGTH_SHORT).show()
        }
    }

    private class Image(val bytes: ByteArray, val mimeType: String)

    private fun load(client: OkHttpClient, url: String, userAgent: String?, referer: String?): Image? {
        if (url.startsWith("data:", ignoreCase = true)) return decodeDataUrl(url)
        val uri = Uri.parse(url)
        if (uri.scheme != "http" && uri.scheme != "https") return null
        val request = Request.Builder().url(url).apply {
            if (!userAgent.isNullOrEmpty()) header("User-Agent", userAgent)
            if (!referer.isNullOrEmpty() && (referer.startsWith("http://") || referer.startsWith("https://"))) header("Referer", referer)
            CookieManager.getInstance().getCookie(url)?.let { header("Cookie", it) }
        }.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            val type = body.contentType()?.let { "${it.type}/${it.subtype}" }
            if (body.contentLength() > MAX_BYTES) return null
            val bytes = body.byteStream().use { it.readNBytesLimited() } ?: return null
            val mimeType = type?.takeIf { it.startsWith("image/") } ?: sniff(bytes) ?: return null
            return Image(bytes, mimeType)
        }
    }

    /** `data:image/png;base64,…` の形の URL（blob の画像も、この形で受け取る）。 */
    private fun decodeDataUrl(url: String): Image? {
        val comma = url.indexOf(',')
        if (comma < 0) return null
        val meta = url.substring(5, comma)
        val payload = url.substring(comma + 1)
        val bytes = if (meta.endsWith(";base64", ignoreCase = true)) {
            Base64.decode(payload, Base64.DEFAULT)
        } else {
            Uri.decode(payload).toByteArray()
        }
        if (bytes.size > MAX_BYTES) return null
        val declared = meta.substringBefore(';').lowercase()
        val mimeType = declared.takeIf { it.startsWith("image/") } ?: sniff(bytes) ?: return null
        return Image(bytes, mimeType)
    }

    private fun save(context: Context, image: Image): File? {
        val dir = File(context.externalCacheDir ?: return null, "clipboard")
        if (!dir.isDirectory && !dir.mkdirs()) return null
        // 前回コピーした画像は残さない。
        dir.listFiles()?.forEach { it.delete() }
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(image.mimeType) ?: "png"
        return File(dir, "image-${System.currentTimeMillis()}.$extension").apply { writeBytes(image.bytes) }
    }

    private fun setClip(context: Context, file: File) {
        val provider = (context.applicationContext as BrowserApplication).providerManager.downloadFileProvider
        val uri = provider.getUriForFile(file)
        val clip = ClipData.newUri(context.contentResolver, context.getString(R.string.copy_image), uri)
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(clip)
    }

    private fun java.io.InputStream.readNBytesLimited(): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_BYTES) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /** Content-Type がない・画像でない応答のとき、先頭のバイトから画像の種類を判定する。 */
    private fun sniff(bytes: ByteArray): String? {
        fun startsWith(vararg values: Int) = bytes.size >= values.size && values.indices.all { (bytes[it].toInt() and 0xFF) == values[it] }
        return when {
            startsWith(0x89, 0x50, 0x4E, 0x47) -> "image/png"
            startsWith(0xFF, 0xD8, 0xFF) -> "image/jpeg"
            startsWith(0x47, 0x49, 0x46, 0x38) -> "image/gif"
            bytes.size >= 12 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(bytes, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "image/webp"
            else -> null
        }
    }
}
