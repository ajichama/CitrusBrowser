/*
 * Copyright (C) 2017-2021 Hazuki
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

package io.github.ajichama.citrusbrowser.download.core.downloader

import android.content.Context
import android.webkit.CookieManager
import androidx.documentfile.provider.DocumentFile
import io.github.ajichama.citrusbrowser.core.MIME_TYPE_UNKNOWN
import io.github.ajichama.citrusbrowser.core.utility.log.ErrorReport
import io.github.ajichama.citrusbrowser.core.utility.storage.toDocumentFile
import io.github.ajichama.citrusbrowser.download.TMP_FILE_SUFFIX
import io.github.ajichama.citrusbrowser.download.core.data.DownloadFileInfo
import io.github.ajichama.citrusbrowser.download.core.data.DownloadRequest
import io.github.ajichama.citrusbrowser.download.core.utils.*
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.IOException

class OkHttpDownloader(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val info: DownloadFileInfo,
    private val downloadRequest: DownloadRequest,
    private val isResume: Boolean = false,
) : Downloader {
    override var downloadListener: Downloader.DownloadListener? = null

    @Volatile
    private var abort = false

    @Volatile
    private var activeCall: Call? = null

    override fun download(): Boolean {
        val requestBuilder = Request.Builder()
            .url(info.url)
            .get()
            .setCookie(CookieManager.getInstance().getCookie(info.url))
            .setReferrer(downloadRequest.referrer)
            .setUserAgent(context, downloadRequest.userAgent)

        val rootFile = info.root.toDocumentFile(context)
        var resumeOffset = 0L
        val downloadFile = if (downloadRequest.isScopedStorageMode) {
            rootFile
        } else {
            val existTmp = rootFile.findFile("${info.name}$TMP_FILE_SUFFIX")

            if (!isResume && existTmp != null) {
                // 名前の決定後に別タスクが作成した一時ファイルも上書きしない。
                info.resumable = false
                info.state = DownloadFileInfo.STATE_UNKNOWN_ERROR
                downloadListener?.onFileDownloadFailed(info, "同名の一時ファイルがあるため保存できません")
                return false
            }

            if (existTmp != null) {
                // 再開時はサービスが状態を更新するため、保存済みファイル長を基準にする。
                resumeOffset = httpResumeOffset(isResume, info.resumable, existTmp.length())
                if (resumeOffset > 0) requestBuilder.header("Range", "bytes=$resumeOffset-")
            }

            val mimeType = if (info.mimeType.isNotEmpty()) info.mimeType else MIME_TYPE_UNKNOWN

            val tmpFile = existTmp ?: rootFile.createFile(mimeType, "${info.name}$TMP_FILE_SUFFIX")

            if (tmpFile == null) {
                info.state = DownloadFileInfo.STATE_UNKNOWN_ERROR
                downloadListener?.onFileDownloadFailed(info, "Unable to create file, file name:${info.name}")
                return false
            }

            tmpFile
        }

        val call = okHttpClient.newCall(requestBuilder.build())
        activeCall = call
        if (abort) call.cancel()
        try {
            if (resumeOffset == 0L) {
                // 新規取得は同名の古い内容を残さない。接続失敗後の再開にも混入させない。
                info.currentSize = 0
                val resumable = info.resumable
                info.resumable = false
                try {
                    val emptyFile = context.contentResolver.openOutputStream(downloadFile.uri, "wt")
                        ?: throw DownloadException("一時ファイルを初期化できません")
                    emptyFile.use { }
                } catch (e: Exception) {
                    throw DownloadException("一時ファイルを初期化できません", e)
                }
                info.resumable = resumable
            }
            call.execute().use { response ->
                val plan = httpDownloadPlan(response.code, response.header("Content-Range"), response.contentLength, resumeOffset)
                info.currentSize = plan.offset
                info.size = plan.totalSize
                if (!info.resumable) {
                    info.resumable = response.isResumable
                }
                // 「w」はプロバイダーにより切り詰めないため、保存し直す場合は明示する。
                val os = context.contentResolver.openOutputStream(downloadFile.uri, if (plan.append) "wa" else "wt")
                    ?: throw DownloadException("Can not open file. mimetype:${info.mimeType}, filename:${info.name}$TMP_FILE_SUFFIX, Exists:${rootFile.exists()}, Writable:${rootFile.canWrite()}, Uri:${rootFile.uri}")

                BufferedOutputStream(os).use { out ->
                    response.body!!.source().use { source ->
                        downloadListener?.onStartDownload(info)
                        var len: Int
                        var progress = info.currentSize
                        val buffer = ByteArray(BUFFER_SIZE)
                        var oldSize: Long
                        var oldSec = System.currentTimeMillis()

                        while (source.read(buffer, 0, BUFFER_SIZE).also { len = it } >= 0) {
                            if (abort) break

                            out.write(buffer, 0, len)
                            progress += len

                            if (System.currentTimeMillis() > oldSec + NOTIFICATION_INTERVAL) {
                                oldSize = info.currentSize
                                info.currentSize = progress

                                val time = System.currentTimeMillis()
                                info.transferSpeed = ((progress - oldSize) * 1000.0 / (time - oldSec)).toLong()
                                downloadListener?.onFileDownloading(info, progress)

                                oldSec = time
                            }
                        }
                        out.flush()
                        info.currentSize = progress
                    }
                }

                if (!abort && info.size >= 0 && info.currentSize != info.size) {
                    throw DownloadException("ダウンロードされたサイズが応答と一致しません")
                }

                var downloadedFile: DocumentFile? = null

                if (abort) {
                    if (downloadRequest.isScopedStorageMode) {
                        if (rootFile.exists()) {
                            context.contentResolver.delete(rootFile.uri, null, null)
                        }
                    } else {
                        deleteTempIfNeed(rootFile)
                    }
                    downloadListener?.onFileDownloadAbort(info)
                    return false
                } else {
                    if (downloadRequest.isScopedStorageMode) {
                        downloadedFile = rootFile
                    } else {
                        if (!downloadFile.renameTo(info.name)) {
                            downloadedFile = rootFile.findFile(info.name)
                            if (downloadedFile == null)
                                throw DownloadException("Rename is failed. name:\"${info.name}\", download path:${rootFile.uri}, mimetype:${info.mimeType}, exists:${rootFile.findFile(info.name) != null}")
                        }

                        downloadedFile = downloadedFile ?: rootFile.findFile(info.name)
                        if (downloadedFile == null) {
                            throw DownloadException("File not found. name:\"${info.name}\", download path:${rootFile.uri}")
                        }
                    }
                }

                info.state = DownloadFileInfo.STATE_DOWNLOADED
                downloadListener?.onFileDownloaded(info, downloadedFile)
                return true
            }

        } catch (e: IOException) {
            if (abort) {
                if (downloadRequest.isScopedStorageMode) {
                    if (rootFile.exists()) context.contentResolver.delete(rootFile.uri, null, null)
                } else {
                    deleteTempIfNeed(rootFile)
                }
                downloadListener?.onFileDownloadAbort(info)
                return false
            }
            ErrorReport.printAndWriteLog(e)
            if (info.resumable) {
                info.state = DownloadFileInfo.STATE_UNKNOWN_ERROR or DownloadFileInfo.STATE_PAUSED
            } else {
                if (downloadFile.exists()) {
                    downloadFile.delete()
                }
                info.state = DownloadFileInfo.STATE_UNKNOWN_ERROR
            }
            if (e is DownloadException) {
                downloadListener?.onFileDownloadFailed(info, e.message)
            } else {
                downloadListener?.onFileDownloadFailed(info, null)
            }
        } finally {
            activeCall = null
        }
        return false
    }

    override fun cancel() {
        info.state = DownloadFileInfo.STATE_CANCELED
        abort = true
        activeCall?.cancel()
    }

    override fun pause() {
        info.state = DownloadFileInfo.STATE_PAUSED
        abort = true
        activeCall?.cancel()
    }

    override fun abort() {
        if (info.resumable && !downloadRequest.isScopedStorageMode) {
            info.state = DownloadFileInfo.STATE_UNKNOWN_ERROR or DownloadFileInfo.STATE_PAUSED
        } else {
            info.resumable = false
            info.state = DownloadFileInfo.STATE_UNKNOWN_ERROR
        }
        abort = true
        // 読み込み待ちの HTTP 通信も直ちに解除し、中断通知まで処理を進める。
        activeCall?.cancel()
    }

    private fun deleteTempIfNeed(rootFile: DocumentFile) {
        if (info.state == DownloadFileInfo.STATE_UNKNOWN_ERROR || info.state == DownloadFileInfo.STATE_CANCELED) {
            rootFile.findFile("${info.name}$TMP_FILE_SUFFIX")?.delete()
        }
    }

    private class DownloadException(message: String, cause: Throwable? = null) : IOException(message, cause)

    companion object {
        private const val BUFFER_SIZE = 1024 * 2
        private const val NOTIFICATION_INTERVAL = 1000
    }
}
