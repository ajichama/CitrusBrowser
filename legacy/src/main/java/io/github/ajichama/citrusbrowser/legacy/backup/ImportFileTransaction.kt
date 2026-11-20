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

import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Properties

/** 複数ファイルの保存失敗時に、自己所有の変更だけを直前の内容へ戻す。 */
internal class ImportFileTransaction(private val recovery: File, private val publication: Publication = AndroidPublication) {
    interface Publication {
        fun recover(target: File): File? = target.takeIf { it.isFile }
        fun write(target: File, bytes: ByteArray)
        fun delete(target: File)
    }
    private object AndroidPublication : Publication {
        override fun recover(target: File): File? {
            if (!target.exists() && !File(target.path + ".bak").exists()) return null
            AtomicFile(target).openRead().use { }
            return target
        }
        override fun write(target: File, bytes: ByteArray) {
            target.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw IOException("保存先を作成できません。") }
            val atomic = AtomicFile(target)
            val output = atomic.startWrite()
            try { output.write(bytes); atomic.finishWrite(output) }
            catch (error: Exception) { atomic.failWrite(output); throw error }
        }
        override fun delete(target: File) = AtomicFile(target).delete()
    }

    var recoveryRequired = false
        private set

    fun <T> execute(changes: Map<File, ByteArray>, expectedBefore: Map<File, ByteArray?> = emptyMap(), afterFiles: () -> T): T {
        data class Before(val target: File, val backup: File?, val oldHash: String?, val newHash: String)
        val before = changes.entries.mapIndexed { index, entry ->
            val previous = publication.recover(entry.key)
            val backup = previous?.let { source -> File(recovery, "before-$index.bin").also { source.copyTo(it) } }
            val oldHash = backup?.let(::hash)
            if (entry.key in expectedBefore && oldHash != expectedBefore[entry.key]?.let(::hash))
                throw IOException("読取後に別の変更が保存されたため取り込みを中止します。")
            Before(entry.key, backup, oldHash, hash(entry.value))
        }
        Properties().apply {
            before.forEachIndexed { index, item ->
                setProperty("target.$index", item.target.absolutePath)
                setProperty("before.$index", item.oldHash ?: "absent")
                setProperty("published.$index", item.newHash)
            }
        }.let { values -> File(recovery, "recovery.properties").outputStream().use { values.store(it, null) } }
        val attempted = arrayListOf<Before>()
        try {
            before.forEach { item ->
                // 競合対象を自分の変更として rollback しないよう、attempted に加える前に照合する。
                val current = item.target.takeIf { it.isFile }?.let(::hash)
                if (current != item.oldHash || File(item.target.path + ".bak").exists() || File(item.target.path + ".new").exists())
                    throw IOException("保存直前に別の変更が検出されたため取り込みを中止します。")
                attempted += item
                publication.write(item.target, changes.getValue(item.target))
                if (!item.target.isFile || hash(item.target) != item.newHash) throw IOException("保存後の内容が一致しません。")
            }
            return afterFiles()
        } catch (error: Throwable) {
            attempted.asReversed().forEach { item ->
                try {
                    val current = item.target.takeIf { it.isFile }?.let(::hash)
                    if (current == item.oldHash) return@forEach
                    if (current != item.newHash) throw IOException("別の書き込みと競合したため退避を保持します。")
                    if (item.backup != null) {
                        publication.write(item.target, item.backup.readBytes())
                        if (hash(item.target) != item.oldHash) throw IOException("元のファイルを復旧できません。")
                    } else {
                        publication.delete(item.target)
                        if (item.target.exists()) throw IOException("未確定ファイルを破棄できません。")
                    }
                } catch (rollback: Throwable) {
                    recoveryRequired = true
                    error.addSuppressed(rollback)
                }
            }
            throw error
        }
    }

    companion object {
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun hash(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
