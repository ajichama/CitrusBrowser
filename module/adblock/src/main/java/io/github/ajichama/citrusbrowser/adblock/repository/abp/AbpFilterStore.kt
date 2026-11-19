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

package io.github.ajichama.citrusbrowser.adblock.repository.abp

import android.util.AtomicFile
import io.github.ajichama.citrusbrowser.adblock.filter.abp.*
import io.github.ajichama.citrusbrowser.adblock.filter.unified.ELEMENT_FILTER_CACHE_HEADER
import io.github.ajichama.citrusbrowser.adblock.filter.unified.FILTER_CACHE_HEADER
import io.github.ajichama.citrusbrowser.adblock.filter.unified.UnifiedFilterSet
import io.github.ajichama.citrusbrowser.adblock.filter.unified.io.ElementWriter
import io.github.ajichama.citrusbrowser.adblock.filter.unified.io.ElementReader
import io.github.ajichama.citrusbrowser.adblock.filter.unified.io.FilterWriter
import io.github.ajichama.citrusbrowser.adblock.filter.unified.io.FilterReader
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.Properties
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** 4種類のキャッシュをまとめて公開し、読取側は開いた世代を閉じるまで保持する。 */
internal class AbpFilterStore(
    private val directory: File,
    private val publication: Publication = AndroidPublication,
) {
    internal interface Publication {
        fun recover(file: File): File?
        fun commit(staged: File, file: File)
        fun delete(file: File)
    }

    private object AndroidPublication : Publication {
        override fun recover(file: File): File? = try {
            AtomicFile(file).openRead().use { }
            file
        } catch (error: FileNotFoundException) {
            if (file.exists() || File(file.path + ".bak").exists()) throw IOException("フィルタ束を開けません。", error)
            null
        }

        override fun commit(staged: File, file: File) {
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try {
                staged.inputStream().use { it.copyTo(output) }
                atomic.finishWrite(output)
            } catch (error: Throwable) {
                atomic.failWrite(output)
                throw error
            }
        }

        override fun delete(file: File) = AtomicFile(file).delete()
    }

    internal class Snapshot(private val zip: ZipFile) : Closeable {
        private val metadata = Properties()
        val parserRevision: Int
        val assetVersion: String?
        val commitId: String
        val sourceUrl: String

        init {
            try {
                val expected = PREFIXES.toSet() + MANIFEST
                val actual = zip.entries().asSequence().map { it.name }.toList()
                if (actual.size != expected.size || actual.toSet() != expected) throw IOException("フィルタ束の構成が不正です。")
                zip.getInputStream(zip.getEntry(MANIFEST)).use { metadata.load(it) }
                if (metadata.getProperty("format") != "1") throw IOException("未対応のフィルタ束です。")
                parserRevision = metadata.getProperty("parserRevision")?.toIntOrNull()
                    ?: throw IOException("パーサー版がありません。")
                assetVersion = metadata.getProperty("assetVersion")
                commitId = metadata.getProperty("commitId") ?: throw IOException("公開識別子がありません。")
                sourceUrl = metadata.getProperty("sourceUrl") ?: throw IOException("購読元がありません。")
                for (prefix in PREFIXES) {
                    val header = if (prefix == ABP_PREFIX_ELEMENT) ELEMENT_FILTER_CACHE_HEADER else FILTER_CACHE_HEADER
                    val entry = zip.getEntry(prefix)
                    if (entry.size < header.toByteArray().size + 4) throw IOException("フィルタキャッシュが途切れています。")
                    open(prefix).use { input ->
                        val supported = if (prefix == ABP_PREFIX_ELEMENT) ElementReader(input).checkHeader() else FilterReader(input).checkHeader()
                        if (!supported) throw IOException("フィルタヘッダーが不正です。")
                    }
                }
            } catch (error: Exception) {
                zip.close()
                throw if (error is IOException) error else IOException("フィルタ束のメタデータが不正です。", error)
            }
        }

        fun open(prefix: String): InputStream {
            if (prefix !in PREFIXES) throw IOException("不正なフィルタ種別です。")
            return zip.getInputStream(zip.getEntry(prefix))
        }

        override fun close() = zip.close()
    }

    fun open(entityId: Int, expectedUrl: String? = null): Snapshot? = synchronized(publicationLock) {
        val file = publication.recover(bundleFile(entityId)) ?: return@synchronized null
        Snapshot(ZipFile(file)).also {
            if (expectedUrl != null && it.sourceUrl != expectedUrl) {
                it.close()
                throw IOException("フィルタ束の購読元が一致しません。")
            }
        }
    }

    fun isCurrent(entityId: Int, sourceUrl: String, assetVersion: String? = null): Boolean = try {
        open(entityId, sourceUrl)?.use { it.parserRevision == PARSER_REVISION && (assetVersion == null || it.assetVersion == assetVersion) } ?: false
    } catch (_: IOException) {
        false
    }

    fun write(entityId: Int, sourceUrl: String, set: UnifiedFilterSet, assetVersion: String? = null): String =
        publish(entityId, sourceUrl, set, assetVersion, keepPrevious = false).use { it.commitId }

    fun beginUpdate(entityId: Int, sourceUrl: String, set: UnifiedFilterSet, assetVersion: String? = null): PendingUpdate =
        publish(entityId, sourceUrl, set, assetVersion, keepPrevious = true)

    private fun publish(entityId: Int, sourceUrl: String, set: UnifiedFilterSet, assetVersion: String?, keepPrevious: Boolean): PendingUpdate {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("フィルタ保存先を作成できません。")
        val staged = File.createTempFile("abp-$entityId-", ".tmp", directory)
        val commitId = UUID.randomUUID().toString()
        var previous: File? = null
        var published = false
        try {
            ZipOutputStream(staged.outputStream().buffered()).use { zip ->
                val writer = FilterWriter()
                val lists = listOf(set.blackList, set.whiteList, set.elementDisableFilter)
                PREFIXES.take(3).zip(lists).forEach { (prefix, filters) ->
                    zip.putNextEntry(ZipEntry(prefix))
                    writer.write(zip, filters)
                    zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry(ABP_PREFIX_ELEMENT))
                ElementWriter().write(zip, set.elementList)
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(MANIFEST))
                Properties().apply {
                    setProperty("format", "1")
                    setProperty("parserRevision", PARSER_REVISION.toString())
                    setProperty("commitId", commitId)
                    setProperty("sourceUrl", sourceUrl)
                    assetVersion?.let { setProperty("assetVersion", it) }
                }.store(zip, null)
                zip.closeEntry()
            }
            Snapshot(ZipFile(staged)).use { }
            synchronized(publicationLock) {
                if (keepPrevious) publication.recover(bundleFile(entityId))?.let { current ->
                    previous = File.createTempFile("abp-$entityId-", ".rollback", directory)
                    current.copyTo(previous!!, overwrite = true)
                }
                publication.commit(staged, bundleFile(entityId))
                // AtomicFile の finishWrite は公開失敗を返さない実装もあるため、識別子まで照合する。
                open(entityId)?.use {
                    if (it.commitId != commitId) throw IOException("フィルタ束を公開できませんでした。")
                } ?: throw IOException("公開後のフィルタ束がありません。")
            }
            published = true
            return PendingUpdate(entityId, commitId, previous)
        } finally {
            staged.delete()
            if (!published) previous?.delete()
        }
    }

    /** DB 更新が失敗した場合も、自分より新しい公開を巻き戻さない。 */
    inner class PendingUpdate internal constructor(
        private val entityId: Int,
        val commitId: String,
        private val previous: File?,
    ) : Closeable {
        private var rollbackFailed = false

        fun rollback() = synchronized(publicationLock) {
            try {
                if (open(entityId)?.use { it.commitId == commitId } != true) return@synchronized
                if (previous != null) {
                    val previousId = Snapshot(ZipFile(previous)).use { it.commitId }
                    publication.commit(previous, bundleFile(entityId))
                    if (open(entityId)?.use { it.commitId == previousId } != true) throw IOException("旧フィルタ束を復旧できませんでした。")
                } else {
                    publication.delete(bundleFile(entityId))
                    if (open(entityId)?.use { true } == true) throw IOException("未確定フィルタ束を破棄できませんでした。")
                }
                rollbackFailed = false
            } catch (error: Exception) {
                // 復旧自体が失敗した場合だけ、手動復旧用に元の束を残す。
                rollbackFailed = true
                throw error
            }
        }

        fun discard() = synchronized(publicationLock) {
            if (open(entityId)?.use { it.commitId == commitId } == true) publication.delete(bundleFile(entityId))
        }

        override fun close() {
            if (!rollbackFailed) previous?.delete()
        }
    }

    fun delete(entityId: Int) = synchronized(publicationLock) {
        publication.delete(bundleFile(entityId))
        PREFIXES.forEach { File(directory, it + entityId).delete() }
    }

    fun invalidatePreviousSource(entityId: Int, previousUrl: String) = synchronized(publicationLock) {
        val owned = try { open(entityId)?.use { it.sourceUrl == previousUrl } == true } catch (_: IOException) { false }
        if (owned) publication.delete(bundleFile(entityId))
        // root 形式は購読元を持たないため、URL 編集後の fallback には再使用しない。
        PREFIXES.forEach { File(directory, it + entityId).delete() }
    }

    private fun bundleFile(entityId: Int) = File(directory, "abp_$entityId.zip")

    companion object {
        // 解釈の変更時に更新する。旧キャッシュは新しい束の公開成功まで保持する。5: scriptlet（+js）の保存を追加。
        const val PARSER_REVISION = 5
        private const val MANIFEST = "manifest.properties"
        private val PREFIXES = listOf(ABP_PREFIX_DENY, ABP_PREFIX_ALLOW, ABP_PREFIX_DISABLE_ELEMENT_PAGE, ABP_PREFIX_ELEMENT)
        private val publicationLock = Any()
    }
}
