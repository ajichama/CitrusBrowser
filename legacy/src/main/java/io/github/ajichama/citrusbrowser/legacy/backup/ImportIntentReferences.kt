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
package io.github.ajichama.citrusbrowser.legacy.backup

import android.content.ComponentName
import android.content.Intent
import io.github.ajichama.citrusbrowser.bookmark.view.BookmarkActivity
import io.github.ajichama.citrusbrowser.core.utility.utils.migrateLegacyBrowserIntentUri
import io.github.ajichama.citrusbrowser.legacy.userjs.UserScriptEditActivity

/** 実在する内部 Activity の参照だけを付け替え、外部アプリの同名 extra は変更しない。 */
internal class ImportIntentReferences(private val packageName: String, private val bookmarks: Map<Long, Long>, private val scripts: Map<Long, Long>) {
    fun remap(value: String): String? { return try {
        // 既存 Action codec は toUri(0) の「#Intent;」形式も保存する。
        val intent = Intent.parseUri(migrateLegacyBrowserIntentUri(value), 0)
        val owned = "io.github.ajichama.citrusbrowser"
        val packages = setOf(owned, "$owned.debug", packageName)
        fun rewrite(target: Intent, depth: Int): Boolean {
            if (depth > 4) return false
            if (target.`package` in packages) target.setPackage(packageName)
            val component = target.component
            if (component != null && component.packageName in packages) {
                target.component = ComponentName(packageName, component.className)
                val key = when (component.className) {
                    BookmarkActivity::class.java.name -> "id"
                    UserScriptEditActivity::class.java.name -> UserScriptEditActivity.EXTRA_USERSCRIPT
                    else -> null
                }
                if (key != null && target.hasExtra(key)) {
                    val id = target.getLongExtra(key, -1)
                    if (id >= 0) {
                        val replacement = (if (key == "id") bookmarks else scripts)[id] ?: return false
                        target.putExtra(key, replacement)
                    }
                }
            }
            return target.selector?.let { rewrite(it, depth + 1) } ?: true
        }
        if (rewrite(intent, 0)) intent.toUri(Intent.URI_INTENT_SCHEME) else null
    } catch (_: Exception) { null } }
}
