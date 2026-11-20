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

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.AtomicFile
import com.squareup.moshi.Moshi
import io.github.ajichama.citrusbrowser.bookmark.repository.BookmarkImport
import io.github.ajichama.citrusbrowser.bookmark.repository.BookmarkManager
import io.github.ajichama.citrusbrowser.legacy.action.ActionDatabase
import io.github.ajichama.citrusbrowser.legacy.speeddial.SpeedDialHtml
import io.github.ajichama.citrusbrowser.legacy.speeddial.SpeedDialManager
import io.github.ajichama.citrusbrowser.legacy.speeddial.io.ImportedSpeedDial
import io.github.ajichama.citrusbrowser.legacy.speeddial.io.SpeedDialImport
import io.github.ajichama.citrusbrowser.legacy.userjs.UserScriptDatabase
import io.github.ajichama.citrusbrowser.search.model.provider.SearchSettings
import io.github.ajichama.citrusbrowser.search.repository.SearchImport
import io.github.ajichama.citrusbrowser.ui.BrowserApplication
import io.github.ajichama.citrusbrowser.ui.provider.IReadItLaterProvider
import io.github.ajichama.citrusbrowser.ui.settings.AppPrefs
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.util.Properties
import java.util.UUID

internal enum class ImportCategory { Settings, Bookmarks, SpeedDial, Scripts, Gestures, ReadItLater }
internal data class ImportPreview(val preferences: Int, val actions: Int, val bookmarks: Int, val speedDial: Int, val ignored: Int,
    val categories: Set<ImportCategory>, val configurations: Int = 0, val scripts: Int = 0, val gestures: Int = 0,
    // ignored は取り込むデータの中で除外する項目。skippedFiles はキャッシュ・履歴など、移行の対象外として読み込まないファイル。
    val readItLater: Int = 0, val skippedFiles: Int = 0)
internal data class ImportResult(val bookmarks: Int, val speedDialAdded: Int, val speedDialDuplicates: Int, val restart: Boolean,
    val normalizationWarning: Boolean = false, val settingsAdded: Int = 0, val scriptsAdded: Int = 0,
    val gesturesAdded: Int = 0, val preserved: Int = 0, val ignored: Int = 0, val readItLaterAdded: Int = 0)

/** 検証済み計画を明示選択で適用する。DB とファイル間の強制終了時の完全原子性は保証しない。 */
internal class PortableImport private constructor(
    private val context: Context, private val staging: File, private val preferences: Map<String, Any>?,
    private val actionFiles: Map<String, ByteArray>, private val bookmarks: List<BookmarkImport.Item>?,
    private val speedDial: List<ImportedSpeedDial>?, private val search: SearchSettings?,
    private val userSettings: List<ImportUserSettings.Prepared>, private val scripts: List<ImportScript.Row>?,
    private val gestures: List<ImportGestures.PairData>, private val multi: ByteArray?,
    private val readItLater: ImportReadItLater.Prepared?, val preview: ImportPreview,
) : Closeable {
    private var keepRecovery = false
    private var applied = false
    private var recoveryDirectory: File? = null
    private val observed = linkedMapOf<File, ByteArray?>()
    val recoveryRequired: Boolean get() = keepRecovery
    private val moshi get() = (context as? BrowserApplication)?.moshi ?: Moshi.Builder().build()

    fun apply(selected: Set<ImportCategory>, folderLabel: String): ImportResult {
        check(!applied)
        require(selected.isNotEmpty() && preview.categories.containsAll(selected))
        applied = true
        val useSettings = ImportCategory.Settings in selected
        val useBookmark = ImportCategory.Bookmarks in selected
        val useDial = ImportCategory.SpeedDial in selected
        val useScripts = ImportCategory.Scripts in selected
        val useGestures = ImportCategory.Gestures in selected
        val useReadItLater = ImportCategory.ReadItLater in selected
        val createdPages = arrayListOf<File>()
        if (useBookmark) readCurrent(ImportArchive.BOOKMARK, 8 * 1024 * 1024)
        val manager = if (useBookmark) BookmarkManager.getInstance(context) else null
        if (manager != null && (manager.file.exists() || File(manager.file.path + ".bak").exists()) && !manager.load())
            throw IOException("現在のブックマークを読み取れません。")
        val mergedBookmarks = manager?.let { BookmarkImport.mergedWithIds(it.root, bookmarks.orEmpty(), folderLabel) }
        // 強制終了で finally が走らなくても、退避を cache 自動清掃へ渡さない。
        val recovery = File(context.filesDir, "import-recovery-${UUID.randomUUID()}").apply { if (!mkdir()) throw IOException("退避先を作成できません。") }
        recoveryDirectory = recovery
        val files = ImportFileTransaction(recovery)
        val prefs = AppPrefs.getPreference(context)
        var writtenValues: Map<String, Any> = emptyMap()
        var beforePrefs: Map<String, Any?> = emptyMap()
        var preferenceWriteAttempted = false
        var databaseCommitAttempted = false
        val targets = linkedMapOf<String, File>()
        if (useDial) targets["dial"] = SpeedDialManager(context).ensureImportDatabase()
        if (useScripts) {
            UserScriptDatabase(context).use { it.writableDatabase }
            targets["scripts"] = context.getDatabasePath("userjs1.db")
        }
        if (useGestures) gestures.forEach { pair ->
            val file = target("app_gestures1/${pair.type}.list.dat")
            file.parentFile?.mkdirs()
            ActionDatabase(context, file).let { helper -> try { helper.prepareImport() } finally { helper.closeImport() } }
            targets["gesture${pair.type}"] = file
        }
        try {
            fun merge(db: SQLiteDatabase?, commit: () -> Unit): ImportResult {
                var ignored = preview.ignored
                var preserved = 0
                var settingsAdded = 0
                var scriptsAdded = 0
                var gesturesAdded = 0
                val changes = linkedMapOf<File, ByteArray>()
                val dialAdded = if (useDial) ImportDatabaseMerge.dial(db!!, "dial", speedDial.orEmpty(), context) else 0
                val scriptMap = if (useScripts) ImportDatabaseMerge.scripts(db!!, "scripts", scripts.orEmpty()).let {
                    scriptsAdded = it.added; preserved += it.duplicates; it.idRemap
                } else emptyMap()
                val remap = ImportIntentReferences(context.packageName, mergedBookmarks?.idRemap.orEmpty(), scriptMap)::remap
                if (manager != null) changes[manager.file] = mergedBookmarks!!.bytes
                val values = if (useSettings) preferences.orEmpty().toMutableMap() else linkedMapOf()
                // 未選択の依存データを、同じ番号の別データへ誤って結び付けない。
                (values["save_bookmark_folder_id"] as? Long)?.takeIf { it >= 0 }?.let { source ->
                    val destination = mergedBookmarks?.idRemap?.get(source)
                    if (destination == null) { values.remove("save_bookmark_folder_id"); ignored++ } else values["save_bookmark_folder_id"] = destination
                }
                if ((!useScripts || scripts == null) && values.remove("userjs_enable") != null) ignored++
                if (!useGestures) listOf("gesture_enable_web", "multi_finger_gesture").forEach { if (values.remove(it) != null) ignored++ }
                if (useSettings) {
                    actionFiles.forEach { (path, bytes) ->
                        val encoded = ImportActions.validate(path, bytes, remap)
                        if (encoded == null) ignored++ else changes[target(path)] = encoded
                    }
                    if (search != null) {
                        val current = readCurrent(SearchImport.PATH, 2 * 1024 * 1024)?.let { SearchImport.decode(it, moshi) }
                            ?: SearchSettings(0, 0, emptyList())
                        val result = SearchImport.merge(current, search)
                        changes[target(SearchImport.PATH)] = SearchImport.encode(result.settings, moshi)
                        settingsAdded += result.settings.items.size - current.items.size
                    }
                    if (userSettings.isNotEmpty()) {
                        val existing = userSettings.mapNotNull { item -> readCurrent(item.path, 2 * 1024 * 1024)?.let { item.path to it } }.toMap()
                        val result = ImportUserSettings.merge(userSettings, existing, remap)
                        result.files.forEach { (path, bytes) -> changes[target(path)] = bytes }
                        settingsAdded += result.added; preserved += result.duplicates; ignored += result.ignored - userSettings.sumOf { it.ignored }
                    }
                }
                if (useGestures) {
                    gestures.forEach { pair ->
                        val alias = "gesture${pair.type}"
                        ImportSqlite.checkTable(db!!, alias, "main_table1", setOf("_id", "action"))
                        val path = "app_gestures1/${pair.type}.lib.dat"
                        val current = readCurrent(path, 8 * 1024 * 1024)?.let(ImportGestureFormat::decode).orEmpty()
                        val ids = hashSetOf<Long>()
                        db.rawQuery("SELECT _id FROM $alias.main_table1 LIMIT 20001", null).use { cursor ->
                            while (cursor.moveToNext()) { if (ids.size >= 20000) throw IOException("現在のジェスチャー操作が多すぎます。"); ids += cursor.getLong(0) }
                        }
                        if (current.any { it.actionId !in ids }) throw IOException("現在のジェスチャーの対応が壊れています。")
                        val result = ImportGestures.merge(pair, current, remap) { action ->
                            db.insertOrThrow("$alias.main_table1", null, ContentValues().apply { put("action", action.toString(Charsets.UTF_8)) })
                        }
                        changes[target(path)] = result.bytes
                        gesturesAdded += result.added; preserved += result.duplicates; ignored += result.ignored - pair.ignored
                    }
                    if (multi != null) {
                        val current = readCurrent(ImportGestures.MULTI, 2 * 1024 * 1024)?.let { ImportGestures.multi(it, strict = true).first }.orEmpty()
                        val incoming = ImportGestures.multi(multi, remap)
                        val result = ImportGestures.mergeMulti(current, incoming.first)
                        changes[target(ImportGestures.MULTI)] = result.bytes
                        gesturesAdded += result.added; preserved += result.duplicates
                        // preview で数えた未知 ID は二重に数えない。
                        ignored += incoming.second - ImportGestures.multi(multi).second
                    }
                }
                var readItLaterAdded = 0
                if (useReadItLater) {
                    val current = readCurrent(ImportReadItLater.INDEX, 8 * 1024 * 1024)?.let(ImportReadItLater::read).orEmpty()
                    val merged = ImportReadItLater.merge(current, readItLater!!) { time -> target(ImportReadItLater.pagePath(time)).exists() }
                    // 保存ページは新しい名前で置くだけで、既存のファイルは上書きしない。索引は他のファイルと同じ手順で更新する。
                    merged.pages.forEach { (source, time) ->
                        val page = target(ImportReadItLater.pagePath(time))
                        page.parentFile?.mkdirs()
                        if (page.exists()) throw IOException("後で読むの保存先が既にあります。")
                        createdPages += page
                        if (!source.renameTo(page)) source.copyTo(page)
                    }
                    if (merged.added > 0) changes[target(ImportReadItLater.INDEX)] = merged.index
                    readItLaterAdded = merged.added; preserved += merged.duplicates
                }
                writtenValues = values
                beforePrefs = prefs.all.filterKeys { it in values }.mapValues { (_, value) -> if (value is Set<*>) value.toSet() else value }
                val prefsFile = target(ImportArchive.PREFERENCES)
                if (values.isNotEmpty() && prefsFile.isFile) prefsFile.copyTo(File(recovery, "preferences-before.xml"))
                Properties().apply { values.keys.forEach { key -> setProperty(key, beforePrefs[key]?.let { "${it.javaClass.name}:$it" } ?: "absent") } }
                    .let { snapshot -> File(recovery, "preferences-before.properties").outputStream().use { snapshot.store(it, null) } }
                files.execute(changes, observed) {
                    if (values.isNotEmpty()) {
                        preferenceWriteAttempted = true
                        val editor = prefs.edit()
                        values.forEach { (key, value) -> ImportPreferences.put(editor, key, value) }
                        if (!editor.commit()) throw IOException("設定を保存できません。")
                    }
                    if (manager != null && !manager.load()) throw IOException("ブックマークを再読込できません。")
                    // endTransaction の失敗もファイル・設定の復旧対象にする。
                    databaseCommitAttempted = db != null
                    commit()
                }
                return ImportResult(if (useBookmark) BookmarkImport.count(bookmarks.orEmpty()) else 0,
                    dialAdded, if (useDial) speedDial.orEmpty().size - dialAdded else 0, useSettings || useGestures || useScripts,
                    settingsAdded = settingsAdded, scriptsAdded = scriptsAdded, gesturesAdded = gesturesAdded, preserved = preserved, ignored = ignored,
                    readItLaterAdded = readItLaterAdded)
            }
            val result = if (targets.isEmpty()) merge(null) { } else ImportSqlite.transaction(File(staging, "transaction.db"), targets, ::merge)
            // 正常完了時だけ、その処理が作った退避を削除する。
            runCatching { recovery.deleteRecursively() }
            recoveryDirectory = null
            if (useDial && result.speedDialAdded > 0) runCatching { SpeedDialHtml.clearCache(context) }
            // 後で読むの一覧はプロバイダーがメモリに持つ。読み直さないと、次の保存で取り込んだ分を上書きしてしまう。
            if (result.readItLaterAdded > 0) runCatching {
                (context as? BrowserApplication)?.providerManager?.readItLaterProvider?.editUri?.let {
                    context.contentResolver.call(it, IReadItLaterProvider.RELOAD, null, null)
                }
            }
            return result
        } catch (error: Exception) {
            keepRecovery = files.recoveryRequired || databaseCommitAttempted
            // 索引を元に戻せた場合だけ、置いた保存ページを消す。復旧が必要な状態では索引が参照している可能性があるため残す。
            if (!keepRecovery) createdPages.forEach { runCatching { it.delete() } }
            if (preferenceWriteAttempted) {
                try {
                    val current = prefs.all
                    val editor = prefs.edit()
                    var restore = false
                    writtenValues.forEach { (key, written) ->
                        when {
                            current[key] == beforePrefs[key] && current.containsKey(key) == beforePrefs.containsKey(key) -> Unit
                            current[key] == written -> { ImportPreferences.put(editor, key, beforePrefs[key]); restore = true }
                            else -> { keepRecovery = true; error.addSuppressed(IOException("別の設定変更を検出したため、その値と退避を保持します。")) }
                        }
                    }
                    if (restore && !editor.commit()) throw IOException("設定の復旧用退避を保持します。")
                } catch (rollback: Exception) { keepRecovery = true; error.addSuppressed(rollback) }
            }
            if (manager != null) try {
                if (!manager.load()) throw IOException("ブックマークの復旧用退避を保持します。")
            } catch (rollback: Exception) { keepRecovery = true; error.addSuppressed(rollback) }
            throw error
        }
    }

    private fun target(path: String): File {
        val root = File(context.applicationInfo.dataDir).canonicalFile
        val file = File(root, path).canonicalFile
        if (!file.path.startsWith(root.path + File.separator)) throw IOException("保存先がアプリ領域外です。")
        return file
    }

    private fun readCurrent(path: String, maximum: Int): ByteArray? {
        val file = target(path)
        if (!file.exists() && !File(file.path + ".bak").exists()) { observed[file] = null; return null }
        AtomicFile(file).openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (output.size().toLong() + read > maximum) throw IOException("現在の構成が上限サイズを超えています。")
                output.write(buffer, 0, read)
            }
            return output.toByteArray().also { observed[file] = it }
        }
    }

    override fun close() {
        if (keepRecovery) {
            // 復旧に必要な退避は files 配下に残す。入力原本は利用者にも保管を依頼する。
            staging.deleteRecursively()
        } else {
            recoveryDirectory?.deleteRecursively()
            staging.deleteRecursively()
        }
    }

    companion object {
        fun prepare(context: Context, uri: Uri): PortableImport {
            val app = context.applicationContext
            val staging = File.createTempFile("citrus-import-", ".stage", app.cacheDir)
            if (!staging.delete() || !staging.mkdir()) throw IOException("一時領域を作成できません。")
            try {
                val archive = (app.contentResolver.openInputStream(uri) ?: throw IOException("文書を開けません。"))
                    .use { ImportArchive.stage(it, staging) }
                val preferences = archive.files[ImportArchive.PREFERENCES]?.readBytes()?.let(ImportPreferences::parse)?.let(ImportPreferences::known)
                var ignored = preferences?.ignored ?: 0
                val actions = linkedMapOf<String, ByteArray>()
                archive.files.filterKeys { it.startsWith("app_action1_") }.forEach { (path, file) ->
                    val validated = ImportActions.validate(path, file.readBytes())
                    if (validated == null) ignored++ else actions[path] = validated
                }
                val bookmarks = archive.files[ImportArchive.BOOKMARK]?.readBytes()?.let(BookmarkImport::json)
                    ?: archive.files[ImportArchive.HTML]?.readBytes()?.let(BookmarkImport::html)
                val dial = archive.files[ImportArchive.DIAL]?.let(SpeedDialImport::read)
                val moshi = (app as? BrowserApplication)?.moshi ?: Moshi.Builder().build()
                val search = archive.files[SearchImport.PATH]?.readBytes()?.let { SearchImport.decode(it, moshi) }
                val userSettings = archive.files.filterKeys { it in ImportUserSettings.paths }.map { (path, file) -> ImportUserSettings.validate(path, file.readBytes()) }
                ignored += userSettings.sumOf { it.ignored }
                val scripts = archive.files[ImportScript.PATH]?.let(ImportScript::read)
                ImportGestureFormat.requirePairs(archive.files.keys)
                val gestures = (0..1).mapNotNull { id ->
                    archive.files["app_gestures1/$id.lib.dat"]?.let { lib -> ImportGestures.pair(id, lib.readBytes(), archive.files.getValue("app_gestures1/$id.list.dat")) }
                }
                ignored += gestures.sumOf { it.ignored }
                val multi = archive.files[ImportGestures.MULTI]?.readBytes()
                val parsedMulti = multi?.let { ImportGestures.multi(it) }
                ignored += parsedMulti?.second ?: 0
                val readItLater = ImportReadItLater.prepare(archive.files[ImportReadItLater.INDEX]?.readBytes(), archive.files)
                ignored += readItLater.ignored
                val categories = buildSet {
                    if (!preferences?.values.isNullOrEmpty() || actions.isNotEmpty() || search != null || userSettings.isNotEmpty()) add(ImportCategory.Settings)
                    if (bookmarks != null) add(ImportCategory.Bookmarks)
                    if (dial != null) add(ImportCategory.SpeedDial)
                    if (scripts != null) add(ImportCategory.Scripts)
                    if (gestures.isNotEmpty() || multi != null) add(ImportCategory.Gestures)
                    if (readItLater.items.isNotEmpty()) add(ImportCategory.ReadItLater)
                }
                if (categories.isEmpty()) throw IOException("対応する取込対象がありません。")
                return PortableImport(app, staging, preferences?.values, actions, bookmarks, dial, search, userSettings, scripts, gestures, multi,
                    readItLater.takeIf { it.items.isNotEmpty() },
                    ImportPreview(preferences?.values?.size ?: 0, actions.size, bookmarks?.let(BookmarkImport::count) ?: 0, dial?.size ?: 0, ignored, categories,
                        (search?.items?.size ?: 0) + userSettings.sumOf { it.count }, scripts?.size ?: 0,
                        gestures.sumOf { it.entries.sumOf { entry -> entry.samples.size } } + (parsedMulti?.first?.size ?: 0),
                        readItLater = readItLater.items.size, skippedFiles = archive.ignored))
            } catch (error: Throwable) { staging.deleteRecursively(); throw error }
        }
    }
}
