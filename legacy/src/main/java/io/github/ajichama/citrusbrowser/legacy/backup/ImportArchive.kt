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

package io.github.ajichama.citrusbrowser.legacy.backup

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipFile

/** 選択された文書を既知の論理名へ分類する。実データ領域へ ZIP を展開しない。 */
internal object ImportArchive {
    const val BOOKMARK = "app_bookmark1/bookmark1.dat"
    const val PREFERENCES = "shared_prefs/main_preference.xml"
    const val DIAL = "databases/speeddial1.db"
    const val HTML = "bookmarks.html"
    val actionFolders = setOf("action1_btn", "action1_sbtn_ary", "action1_custombar", "action1_menu",
        "action1_hbtn", "action1_tab", "action1_flick", "action1_double_flick", "action1_lpress", "action1_qc", "action1_webswipe")

    data class Staged(val files: Map<String, File>, val ignored: Int)

    /**
     * 取込の上限。Yuzu の標準バックアップはアプリのデータ領域をまるごと含み、ファイル数も展開後の大きさも大きくなりうる。
     * 取り出すのは既知の小さなファイルだけなので、取り出す分と読み飛ばす分で上限を分ける。
     * 読み飛ばす分も ZIP の整合性を確かめるために展開するため、合計に上限を置く（展開爆弾で処理が終わらないことを防ぐ）。
     */
    internal data class Limits(
        val archiveBytes: Long = 1L shl 30,
        val entries: Int = 65_536,
        val stagedEntryBytes: Long = 32L * 1024 * 1024,
        val stagedTotalBytes: Long = 64L * 1024 * 1024,
        // 後で読むの保存ページ。件数が多く、1件が大きいこともある。
        val pageEntryBytes: Long = 64L * 1024 * 1024,
        val pageTotalBytes: Long = 1L shl 30,
        val skippedTotalBytes: Long = 4L shl 30,
    )

    fun stage(input: InputStream, directory: File): Staged = stage(input, directory, Limits())

    internal fun stage(input: InputStream, directory: File, limits: Limits): Staged {
        require(directory.isDirectory)
        val files = linkedMapOf<String, File>()
        var total = 0L
        var skipped = 0L
        var pages = 0L
        var ignored = 0
        fun copy(source: InputStream, name: String?) {
            val destination = name?.let { File.createTempFile("entry-", ".data", directory) }
            var count = 0L
            try {
                val output = destination?.outputStream()
                try {
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        count += read
                        if (name != null && ImportReadItLater.isPage(name)) {
                            pages += read
                            if (count > limits.pageEntryBytes || pages > limits.pageTotalBytes) throw IOException("後で読むのページが上限サイズを超えています。")
                        } else if (name != null) {
                            total += read
                            if (count > limits.stagedEntryBytes || total > limits.stagedTotalBytes) throw IOException("バックアップの展開サイズが上限を超えています。")
                        } else {
                            skipped += read
                            if (skipped > limits.skippedTotalBytes) throw IOException("バックアップの展開サイズが上限を超えています。")
                        }
                        output?.write(buffer, 0, read)
                    }
                } finally { output?.close() }
                if (name != null) files[name] = destination!! else ignored++
            } catch (error: Throwable) {
                destination?.delete()
                throw error
            }
        }
        PushbackInputStream(input, 32).use { stream ->
            val prefix = ByteArray(32)
            var read = 0
            while (read < prefix.size) {
                val count = stream.read(prefix, read, prefix.size - read)
                if (count < 0) break
                if (count > 0) read += count
            }
            if (read < 1) throw IOException("空の文書です。")
            stream.unread(prefix, 0, read)
            if (read >= 2 && prefix[0] == 0x50.toByte() && prefix[1] == 0x4b.toByte()) {
                // 中央ディレクトリが無い途中欠損 ZIP も、適用前に拒否する。
                val archive = File.createTempFile("archive-", ".zip", directory)
                try {
                    archive.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var compressed = 0L
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            compressed += count
                            if (compressed > limits.archiveBytes) throw IOException("バックアップが上限サイズを超えています。")
                            output.write(buffer, 0, count)
                        }
                    }
                    val centralNames = ZipFile(archive).use { zip ->
                        val list = zip.entries().asSequence().take(limits.entries + 1).map { checkedPath(it.name) }.toList()
                        if (list.size > limits.entries || list.toSet().size != list.size) throw IOException("ZIP の構成が不正です。")
                        list.toSet()
                    }
                    ZipInputStream(archive.inputStream()).use { zip ->
                    val names = hashSetOf<String>()
                    var entries = 0
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (++entries > limits.entries) throw IOException("バックアップ内のファイル数が上限を超えています。")
                        val path = checkedPath(entry.name)
                        if (!names.add(path)) throw IOException("バックアップ内に重複したファイルがあります。")
                        if (entry.isDirectory) {
                            // directory 名の圧縮 payload も上限を通してから閉じる。
                            val before = skipped
                            copy(zip, null)
                            ignored--
                            if (skipped != before) throw IOException("ディレクトリにデータが含まれています。")
                            zip.closeEntry()
                            continue
                        }
                        val logical = logicalName(path)
                        if (logical != null && files.containsKey(logical)) throw IOException("同じ取込対象が複数あります。")
                        copy(zip, logical)
                        zip.closeEntry()
                    }
                    if (names != centralNames) throw IOException("ZIP のエントリ一覧が一致しません。")
                    }
                } finally {
                    archive.delete()
                }
            } else {
                val text = prefix.copyOf(read).toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\r', '\n', '\t')
                val name = when {
                    text.startsWith("[") -> BOOKMARK
                    text.contains("<!DOCTYPE", ignoreCase = true) -> HTML
                    text.startsWith("<") -> PREFERENCES
                    text.startsWith("SQLite format 3") -> DIAL
                    else -> throw IOException("対応する JSON・設定 XML・SQLite・ZIP ではありません。")
                }
                copy(stream, name)
            }
        }
        if (files.isEmpty()) throw IOException("設定・ブックマーク・スピードダイヤルが見つかりません。")
        return Staged(files, ignored)
    }

    private fun checkedPath(value: String): String {
        val path = value.removeSuffix("/")
        // 名前のとおりに書き出すことはない。Yuzu のデータ領域には「:」を含むファイル名があるため、
        // ドライブ名・スキームに見える先頭の区切りだけを拒否する。
        if (path.isEmpty() || path.startsWith('/') || path.contains('\\') || path.substringBefore('/').contains(':') ||
            path.split('/').any { it.isEmpty() || it == "." || it == ".." } || path.any { it.code < 32 }) {
            throw IOException("バックアップ内のパスが不正です。")
        }
        return path
    }

    private fun logicalName(path: String): String? = when {
        path == BOOKMARK || path == PREFERENCES || path == DIAL || path == ImportScript.PATH ||
            path == io.github.ajichama.citrusbrowser.search.repository.SearchImport.PATH ||
            path in ImportUserSettings.paths || path in ImportGestures.paths ||
            path == ImportReadItLater.INDEX || ImportReadItLater.isPage(path) -> path
        path == "speeddial1.db" -> DIAL
        path.matches(Regex("app_(${actionFolders.joinToString("|")})/[0-9]+\\.dat")) -> path
        else -> null
    }
}
