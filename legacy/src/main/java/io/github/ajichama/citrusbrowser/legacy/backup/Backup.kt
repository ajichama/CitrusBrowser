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

package io.github.ajichama.citrusbrowser.legacy.backup

import android.content.Context
import android.net.Uri
import io.github.ajichama.citrusbrowser.core.utility.extensions.forEach
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

fun BackupManager.backup(context: Context, uri: Uri): Boolean {
    try {
        val os = context.contentResolver.openOutputStream(uri) ?: return false
        ZipOutputStream(os).use { zip ->
            val rootUri = root.toURI()

            backUpFiles.forEach { file ->
                val name = rootUri.relativize(file.toURI()).toString()
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use {
                    it.copyTo(zip)
                }
            }
        }
        return true
    } catch (e: IOException) {
        return false
    }
}
