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

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** 版・ハッシュを固定した公式 XPI だけを、Docker Volume 上の assets へ展開する。 */
@CacheableTask
abstract class PrepareBuiltinUblock : DefaultTask() {
    @get:Input abstract val extensionVersion: Property<String>
    @get:Input abstract val archiveSha256: Property<String>
    @get:Input abstract val archiveUrl: Property<String>
    @get:Input abstract val sourceUrl: Property<String>
    @get:Input abstract val offline: Property<Boolean>
    @get:LocalState abstract val downloadDirectory: DirectoryProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction fun generate() {
        val cache = downloadDirectory.get().asFile.apply { mkdirs() }
        val archive = File(cache, "ublock-origin-${extensionVersion.get()}.xpi")
        BuiltinUblockAssets.fetch(archiveUrl.get(), archiveSha256.get(), archive, offline.get())
        BuiltinUblockAssets.generate(archive, outputDirectory.get().asFile, extensionVersion.get(),
            archiveSha256.get(), archiveUrl.get(), sourceUrl.get())
    }
}

internal object BuiltinUblockAssets {
    private const val MAX_ARCHIVE = 16L * 1024 * 1024
    private const val MAX_TOTAL = 96L * 1024 * 1024
    private const val MAX_ENTRY = 16L * 1024 * 1024
    private const val MAX_ENTRIES = 4096
    const val EXTENSION_ID = "uBlock0@raymondhill.net"

    fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun fetch(url: String, expected: String, destination: File, offline: Boolean) {
        require(expected.matches(Regex("[0-9a-f]{64}")))
        if (destination.isFile && destination.length() <= MAX_ARCHIVE && hash(destination) == expected) return
        if (offline) throw GradleException("固定 uBO XPI の検証済みキャッシュがありません。明示的な通信可能ビルドで取得してください。")
        val part = Files.createTempFile(destination.parentFile.toPath(), "ublock-", ".part").toFile()
        try {
            var uri = URI(url)
            var finished = false
            val deadline = System.nanoTime() + 120_000_000_000L
            for (redirect in 0..5) {
                if (uri.scheme != "https" || uri.userInfo != null) throw IOException("XPI の取得先は HTTPS に限定します。")
                val connection = uri.toURL().openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("User-Agent", "CitrusBrowser-BuiltinExtensionBuild/1")
                try {
                    val status = connection.responseCode
                    if (status in setOf(301, 302, 303, 307, 308)) {
                        val location = connection.getHeaderField("Location") ?: throw IOException("取得先の転送が不正です。")
                        uri = uri.resolve(location)
                        continue
                    }
                    if (status != 200 || connection.contentLengthLong > MAX_ARCHIVE) throw IOException("固定 XPI を取得できません。HTTP $status")
                    var size = 0L
                    connection.inputStream.use { input -> part.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            if (System.nanoTime() > deadline) throw IOException("XPI 取得の上限時間を超えました。")
                            val count = input.read(buffer)
                            if (count < 0) break
                            size += count
                            if (size > MAX_ARCHIVE) throw IOException("XPI の容量が上限を超えました。")
                            output.write(buffer, 0, count)
                        }
                    } }
                    finished = true
                    break
                } finally { connection.disconnect() }
            }
            if (!finished || hash(part) != expected) throw IOException("XPI の SHA-256 が固定値と一致しません。")
            Files.move(part.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally { part.delete() }
    }

    fun generate(archive: File, output: File, version: String, expected: String, archiveUrl: String, sourceUrl: String) {
        require(version.matches(Regex("[0-9]+(?:\\.[0-9]+){1,3}")))
        require(expected.matches(Regex("[0-9a-f]{64}")))
        if (archive.length() > MAX_ARCHIVE || hash(archive) != expected) throw IOException("固定 XPI の検証に失敗しました。")
        val parent = output.absoluteFile.parentFile.apply { mkdirs() }
        if (Files.isSymbolicLink(output.toPath())) throw IOException("出力先をリンクへ置き換えられません。")
        val stage = Files.createTempDirectory(parent.toPath(), "builtin-ublock-stage-").toFile()
        try {
            val extension = File(stage, "ublock").apply { mkdir() }
            var total = 0L
            var count = 0
            val names = hashSetOf<String>()
            ZipFile(archive).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    if (++count > MAX_ENTRIES) throw IOException("XPI のエントリ数が上限を超えました。")
                    val entry = entries.nextElement()
                    val name = entry.name.removeSuffix("/")
                    if (name.isEmpty() || name.startsWith('/') || name.contains('\\') || name.contains(':') ||
                        name.any { it.code < 32 } || name.split('/').any { it.isEmpty() || it == "." || it == ".." } || !names.add(name))
                        throw IOException("XPI のパスが不正または重複しています。")
                    if (entry.size > MAX_ENTRY) throw IOException("XPI のエントリが大きすぎます。")
                    val target = File(extension, name)
                    if (!target.canonicalPath.startsWith(extension.canonicalPath + File.separator)) throw IOException("XPI の展開先が不正です。")
                    if (entry.isDirectory) target.mkdirs() else target.parentFile.mkdirs()
                    val crc = CRC32()
                    var size = 0L
                    zip.getInputStream(entry).use { input ->
                        val writer = if (entry.isDirectory) null else target.outputStream()
                        try {
                            val buffer = ByteArray(8192)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                size += read; total += read
                                if (size > MAX_ENTRY || total > MAX_TOTAL) throw IOException("XPI の展開容量が上限を超えました。")
                                crc.update(buffer, 0, read)
                                writer?.write(buffer, 0, read)
                            }
                        } finally { writer?.close() }
                    }
                    if ((entry.isDirectory && size != 0L) || entry.crc != crc.value || entry.size != size)
                        throw IOException("XPI のエントリ整合性を確認できません。")
                }
            }
            val manifest = File(extension, "manifest.json")
            val license = File(extension, "LICENSE.txt")
            if (!manifest.isFile || manifest.length() > 64 * 1024 || !license.isFile || license.length() == 0L)
                throw IOException("uBO の manifest または GPL 本文がありません。")
            val text = manifest.readText(Charsets.UTF_8)
            // SHA 固定済み原本の版・ID の取り違え確認であり、任意の JSON 検証器ではない。
            if (!Regex("\"version\"\\s*:\\s*\"${Regex.escape(version)}\"").containsMatchIn(text) ||
                !Regex("\"id\"\\s*:\\s*\"${Regex.escape(EXTENSION_ID)}\"").containsMatchIn(text))
                throw IOException("uBO の版または ID が固定した契約と一致しません。")
            val notice = """
                内蔵 uBlock Origin $version
                作者: Raymond Hill & contributors
                ライセンス: GNU General Public License v3.0 only
                拡張 ID: $EXTENSION_ID
                公式配布元: $archiveUrl
                配布原本 SHA-256: $expected
                対応ソース: $sourceUrl
                GPL 本文: ublock/LICENSE.txt
                同梱の第三者ライセンスは拡張内の元パスに保持しています。
                拡張のコード・manifest・署名エントリは原本から改変していません。
                コード更新は Citrus Gecko Preview の APK 更新で配布します。
                フィルタリスト更新・利用者設定は uBO 自身の画面と Gecko profile に属します。
                拡張の通常 install 時の暗号署名検証と、ここでの原本 SHA 照合は異なる検証です。
            """.trimIndent() + "\n"
            File(stage, "ublock-provenance.txt").writeText(notice, Charsets.UTF_8)
            val previous = Files.createTempDirectory(parent.toPath(), "builtin-ublock-before-").toFile().apply { delete() }
            try {
                if (output.exists()) Files.move(output.toPath(), previous.toPath())
                try { Files.move(stage.toPath(), output.toPath()) }
                catch (error: Exception) {
                    if (!output.exists() && previous.exists()) Files.move(previous.toPath(), output.toPath())
                    throw error
                }
                previous.deleteRecursively()
            } catch (error: Exception) {
                throw GradleException("同梱 assets の公開に失敗しました。旧出力・退避: $previous", error)
            }
        } finally { stage.deleteRecursively() }
    }
}
