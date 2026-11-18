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
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/** AAPT を経た最終 APK と、SHA 検証済み原本からの生成 assets を照合する。 */
@CacheableTask
abstract class VerifyBuiltinUblockPackaging : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val expectedAssets: DirectoryProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apkDirectory: DirectoryProperty
    @get:OutputFile abstract val reportFile: RegularFileProperty

    @TaskAction fun verify() {
        val apks = apkDirectory.get().asFile.listFiles()?.filter { it.isFile && it.extension == "apk" }?.sortedBy { it.name }.orEmpty()
        if (apks.isEmpty()) throw IOException("照合する APK がありません。")
        val report = apks.joinToString("\n", postfix = "\n") { apk ->
            "${apk.name}: ${BuiltinUblockPackaging.verify(expectedAssets.get().asFile, apk)} files/bytes OK"
        }
        reportFile.get().asFile.apply { parentFile.mkdirs(); writeText(report, Charsets.UTF_8) }
    }
}

internal object BuiltinUblockPackaging {
    fun verify(expectedAssets: File, apk: File): Int {
        val expected = expectedAssets.walkTopDown().filter { it.isFile }.associateBy {
            "assets/" + it.relativeTo(expectedAssets).invariantSeparatorsPath
        }
        if (expected["assets/ublock/manifest.json"] == null || expected["assets/ublock/LICENSE.txt"] == null ||
            expected["assets/ublock-provenance.txt"] == null) throw IOException("uBO 生成 assets が不完全です。")
        ZipFile(apk).use { zip ->
            val names = hashSetOf<String>()
            val actual = zip.entries().asSequence().filterNot { it.isDirectory }.filter {
                it.name.startsWith("assets/ublock/") || it.name in expected
            }.associateBy { entry ->
                if (!names.add(entry.name)) throw IOException("APK の uBO assets が重複しています: ${entry.name}")
                entry.name
            }
            val missing = expected.keys - actual.keys
            val unexpected = actual.keys - expected.keys
            if (missing.isNotEmpty() || unexpected.isNotEmpty()) {
                throw IOException("APK の uBO assets が一致しません。欠落${missing.size}件/余分${unexpected.size}件: " +
                    (missing.sorted().take(8) + unexpected.sorted().take(8)).joinToString(", "))
            }
            expected.forEach { (name, source) ->
                val entry = actual.getValue(name)
                if (entry.size != source.length() || !source.inputStream().use { original ->
                    zip.getInputStream(entry).use { packaged -> digest(original).contentEquals(digest(packaged)) }
                }) throw IOException("APK の uBO assets の内容が一致しません: $name")
            }
        }
        return expected.size
    }

    private fun digest(input: InputStream): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return hash.digest()
            hash.update(buffer, 0, count)
        }
    }
}
