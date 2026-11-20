package io.github.ajichama.citrusbrowser.download.core.downloader

import java.io.IOException
import java.net.HttpURLConnection

internal data class HttpDownloadPlan(val append: Boolean, val offset: Long, val totalSize: Long)

/** 再開操作だけが既存の部分ファイルを引き継ぐ。新規取得は同名でも別の内容として扱う。 */
internal fun httpResumeOffset(isResume: Boolean, resumable: Boolean, partialFileSize: Long): Long =
    if (isResume && resumable && partialFileSize > 0) partialFileSize else 0

/** HTTP 応答を検証し、既存ファイルへの追記可否と進捗の起点を決める。 */
internal fun httpDownloadPlan(
    code: Int,
    contentRange: String?,
    contentLength: Long,
    requestedOffset: Long,
): HttpDownloadPlan {
    if (code == HttpURLConnection.HTTP_OK) {
        // Range を無視された場合は全体を保存し直し、古い進捗を加算しない。
        return HttpDownloadPlan(false, 0, contentLength)
    }
    if (code != HttpURLConnection.HTTP_PARTIAL || requestedOffset <= 0) {
        throw IOException("ダウンロード応答が不正です: HTTP $code")
    }
    val range = contentRange?.let { CONTENT_RANGE.matchEntire(it.trim()) }
        ?: throw IOException("Content-Range が不正です")
    val start = range.groupValues[1].toLongOrNull()
    val end = range.groupValues[2].toLongOrNull()
    val total = range.groupValues[3].toLongOrNull()
    if (start == null || end == null || total == null || start != requestedOffset ||
        end < start || total <= end || end != total - 1 ||
        (contentLength >= 0 && contentLength != end - start + 1)) {
        // 単一の末尾 Range だけを保存する。範囲がずれた応答は書き込み前に拒否する。
        throw IOException("Content-Range が再開位置と一致しません")
    }
    return HttpDownloadPlan(true, start, total)
}

private val CONTENT_RANGE = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)", RegexOption.IGNORE_CASE)
