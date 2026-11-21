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

package io.github.ajichama.citrusbrowser.legacy.webkit

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import io.github.ajichama.citrusbrowser.core.utility.utils.getMimeType
import io.github.ajichama.citrusbrowser.ui.BrowserApplication
import java.io.File

/**
 * `<input type=file>` のファイル選択。
 *
 * 画像・動画を受け付ける入力欄では、ファイルを選ぶアプリに加えてカメラで撮れるようにする。
 * `capture` 属性があれば、選択の画面を出さずにカメラを開く（Chrome と同じ。#13）。
 * WebView は結果を受け取るまで次の選択を出さないので、どの経路でも必ず結果を返す。
 */
class WebUploadHandler {
    private var uploadMsg: ValueCallback<Array<Uri>?>? = null

    /** 今回の選択のために用意した撮影の出力先。撮らなかったものは結果を受け取ったときに消す。 */
    private val captures = mutableListOf<Capture>()

    private class Capture(val file: File, val uri: Uri)

    /** 選択を始める。前の選択が終わっていなければ、先に空の結果を返す。 */
    fun start(uploadMsg: ValueCallback<Array<Uri>?>) {
        cancel()
        this.uploadMsg = uploadMsg
    }

    fun onActivityResult(resultCode: Int, intent: Intent?) {
        val callback = uploadMsg ?: return
        uploadMsg = null
        callback.onReceiveValue(parseResult(resultCode, intent))
    }

    /** 選択の画面を開けなかったときなどに、空の結果を返して終える。 */
    fun cancel() {
        uploadMsg?.run {
            onReceiveValue(null)
            uploadMsg = null
        }
        discardCaptures(keep = null)
    }

    fun destroy() {
        cancel()
    }

    /** この入力欄でカメラを使う候補があるか（画像・動画、または種類の指定がない）。 */
    fun acceptsCapture(params: WebChromeClient.FileChooserParams): Boolean {
        val types = params.normalizedAcceptTypes()
        return types.acceptsImage() || types.acceptsVideo()
    }

    /**
     * 選択の画面（またはカメラ）を開く Intent を作る。
     * [cameraAllowed] はカメラの権限が許可されているか（`CAMERA` を宣言しているので、無いと撮影を頼めない）。
     */
    fun createIntent(context: Context, params: WebChromeClient.FileChooserParams, cameraAllowed: Boolean, title: CharSequence): Intent {
        val types = params.normalizedAcceptTypes()
        val imageCaptures = if (cameraAllowed && types.acceptsImage()) captureIntents(context, MediaStore.ACTION_IMAGE_CAPTURE, "jpg") else emptyList()
        val videoCaptures = if (cameraAllowed && types.acceptsVideo()) captureIntents(context, MediaStore.ACTION_VIDEO_CAPTURE, "mp4") else emptyList()

        // capture 属性があれば、画像を優先してカメラを直接開く（カメラアプリが1つのときだけ。複数なら選ばせる）。
        if (params.isCaptureEnabled) {
            val direct = imageCaptures.ifEmpty { videoCaptures }
            if (direct.size == 1) return direct[0]
            if (direct.isNotEmpty()) {
                return Intent.createChooser(direct[0], title).apply {
                    putExtra(Intent.EXTRA_INITIAL_INTENTS, direct.drop(1).toTypedArray())
                }
            }
        }

        val content = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            if (types.isNotEmpty()) {
                putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
            }
            type = "*/*"
        }
        return Intent.createChooser(content, title).apply {
            val extra = imageCaptures + videoCaptures
            if (extra.isNotEmpty()) putExtra(Intent.EXTRA_INITIAL_INTENTS, extra.toTypedArray())
        }
    }

    /**
     * カメラアプリごとに、出力先を指定した撮影の Intent を作る。
     * 選択の画面を経由すると書き込みの許可が渡らないことがあるので、アプリごとに許可を与える。
     */
    private fun captureIntents(context: Context, action: String, extension: String): List<Intent> {
        val pm = context.packageManager
        val apps = pm.queryIntentActivities(Intent(action), 0)
        if (apps.isEmpty()) return emptyList()
        val capture = createCapture(context, extension) ?: return emptyList()
        return apps.map { info ->
            val pkg = info.activityInfo.packageName
            context.grantUriPermission(pkg, capture.uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            Intent(action).apply {
                setClassName(pkg, info.activityInfo.name)
                putExtra(MediaStore.EXTRA_OUTPUT, capture.uri)
                clipData = ClipData.newRawUri(null, capture.uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    /** アプリ専用の外部キャッシュに空のファイルを作り、渡す URI を用意する（ギャラリーには残らない）。 */
    private fun createCapture(context: Context, extension: String): Capture? {
        val dir = File(context.externalCacheDir ?: return null, CAPTURE_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        // 送信前のフォームが前に撮った写真を使っているかもしれないので、古いものだけ消す。
        val expired = System.currentTimeMillis() - CAPTURE_KEEP_MS
        dir.listFiles()?.forEach { if (it.lastModified() < expired) it.delete() }
        val file = File(dir, "capture-${System.currentTimeMillis()}.$extension")
        if (!file.createNewFile()) return null
        val provider = (context.applicationContext as BrowserApplication).providerManager.downloadFileProvider
        return Capture(file, provider.getUriForFile(file)).also { captures.add(it) }
    }

    private fun parseResult(resultCode: Int, intent: Intent?): Array<Uri>? {
        if (resultCode != Activity.RESULT_OK) {
            discardCaptures(keep = null)
            return null
        }
        // ファイルを選んだとき、または撮った動画を自分の URI で返すカメラアプリ。
        val picked = intent?.let { data ->
            data.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } }
                ?: data.data?.let { listOf(it) }
        }
        if (!picked.isNullOrEmpty()) {
            discardCaptures(keep = null)
            return picked.toTypedArray()
        }
        // 出力先を指定した撮影は、結果に URI を入れずに戻る。書き込まれたファイルを返す。
        val shot = captures.firstOrNull { it.file.length() > 0 }
        discardCaptures(keep = shot)
        return shot?.let { arrayOf(it.uri) }
    }

    /** 使わなかった撮影の出力先（空のファイル）を消す。 */
    private fun discardCaptures(keep: Capture?) {
        captures.forEach { if (it !== keep) it.file.delete() }
        captures.clear()
    }

    private fun WebChromeClient.FileChooserParams.normalizedAcceptTypes(): List<String> =
        acceptTypes.orEmpty()
            .flatMap { it.split(',') }
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            // 拡張子（.png など）は MIME 型に直す。
            .map { if (it.contains('/')) it else getMimeType(it) }

    /** 種類の指定がない、または画像を受け付ける。 */
    private fun List<String>.acceptsImage() = isEmpty() || any { it == "*/*" || it.startsWith("image/") }

    /** 種類の指定がない、または動画を受け付ける。 */
    private fun List<String>.acceptsVideo() = isEmpty() || any { it == "*/*" || it.startsWith("video/") }

    companion object {
        private const val CAPTURE_DIR = "upload-capture"
        private const val CAPTURE_KEEP_MS = 24L * 60 * 60 * 1000
    }
}
