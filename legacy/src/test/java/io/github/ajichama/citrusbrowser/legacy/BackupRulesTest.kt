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

package io.github.ajichama.citrusbrowser.legacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * バックアップの規則（Android 11 以下と、Android 12 以降のクラウド・端末間の移行）で、
 * 除くものが同じで、ログインの状態とキャッシュを除いていることを確かめる（#27）。
 */
class BackupRulesTest {

    private val xml = File(
        generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle").isFile },
        "legacy/src/main/res/xml",
    )

    @Test
    fun allRulesExcludeTheSameEntries() {
        val legacy = excludes(File(xml, "backup_settings.xml").readText())
        val modern = File(xml, "data_extraction_rules.xml").readText()
        assertEquals("クラウドのバックアップ", legacy, excludes(section(modern, "cloud-backup")))
        assertEquals("端末間の移行", legacy, excludes(section(modern, "device-transfer")))
    }

    @Test
    fun loginStateAndCachesAreExcluded() {
        val legacy = excludes(File(xml, "backup_settings.xml").readText())
        for (entry in listOf("root:app_webview", "root:app_adblock_filter", "root:app_appcache", "sharedpref:AwOriginVisitLoggerPrefs.xml")) {
            assertTrue("除いていない: $entry", entry in legacy)
        }
    }

    private fun section(text: String, name: String): String =
        requireNotNull(Regex("<$name[^>]*>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(text)) { "$name がありません" }.groupValues[1]

    private fun excludes(text: String): Set<String> =
        EXCLUDE.findAll(text.replace(COMMENT, "")).map { "${it.groupValues[1]}:${it.groupValues[2]}" }.toSet()

    private companion object {
        val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
        val EXCLUDE = Regex("<exclude\\s+domain=\"([^\"]+)\"\\s+path=\"([^\"]+)\"\\s*/>")
    }
}
