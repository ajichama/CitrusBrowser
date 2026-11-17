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

import android.content.ComponentName
import android.content.Intent
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.net.Uri
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ajichama.citrusbrowser.bookmark.item.BookmarkSite
import io.github.ajichama.citrusbrowser.bookmark.item.BookmarkFolder
import io.github.ajichama.citrusbrowser.bookmark.view.BookmarkActivity
import io.github.ajichama.citrusbrowser.legacy.userjs.UserScriptDatabase
import io.github.ajichama.citrusbrowser.legacy.userjs.UserScriptEditActivity
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import io.github.ajichama.citrusbrowser.search.repository.SearchImport
import okio.Buffer
import io.github.ajichama.citrusbrowser.bookmark.repository.BookmarkManager
import io.github.ajichama.citrusbrowser.legacy.action.SingleAction
import io.github.ajichama.citrusbrowser.legacy.action.manager.SingleActionFile
import io.github.ajichama.citrusbrowser.legacy.speeddial.SpeedDial
import io.github.ajichama.citrusbrowser.legacy.speeddial.SpeedDialManager
import io.github.ajichama.citrusbrowser.legacy.speeddial.io.SpeedDialImport
import io.github.ajichama.citrusbrowser.ui.settings.AppPrefs
import io.github.ajichama.citrusbrowser.ui.utils.SearchTemplates
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.lang.reflect.Modifier
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class PortableImportTest {
    @Test fun settingsAndNativeBookmarksMergeWithoutDeletingOtherDataAndNormalizeLegacyPreferences() = fixture { f ->
        val prefs = AppPrefs.getPreference(f.context)
        prefs.edit().putBoolean("javascript", true).putInt("_lastLaunchVersion", 123).putString("local-only", "keep")
            .putString("theme_setting", "keep-theme").putString("download_folder", "keep-folder").commit()
        val manager = BookmarkManager.getInstance(f.context)
        manager.root.add(BookmarkSite("keep", "https://keep.invalid/", 100)); assertTrue(manager.save())
        val input = f.zip(mapOf(ImportArchive.PREFERENCES to """<map><boolean name="javascript" value="false"/><int name="_lastLaunchVersion" value="999"/><int name="web_theme" value="3"/><string name="search_url">http://www.google.com/m?q=%s</string><string name="theme_setting">missing-theme</string><string name="download_folder">content://old-grant</string><string name="unknown-key">skip</string></map>""".toByteArray(),
            ImportArchive.BOOKMARK to """[{"0":2,"1":"imported","3":100,"2":"https://new.invalid/"}]""".toByteArray(),
            "app_action1_hbtn/1.dat" to "[[1020,null]]".toByteArray(), "databases/unknown.db" to byteArrayOf(1)))
        PortableImport.prepare(f.context, Uri.fromFile(input)).use { plan ->
            assertTrue(plan.preview.ignored >= 4)
            val result = plan.apply(setOf(ImportCategory.Settings, ImportCategory.Bookmarks), "imported-folder")
            assertEquals(1, result.bookmarks)
        }
        assertFalse(prefs.getBoolean("javascript", true)); assertEquals(123, prefs.getInt("_lastLaunchVersion", -1))
        assertEquals("keep", prefs.getString("local-only", null)); assertEquals("keep-theme", prefs.getString("theme_setting", null))
        assertEquals("keep-folder", prefs.getString("download_folder", null)); assertFalse(prefs.contains("unknown-key"))
        assertEquals(2, manager.root.size()); assertEquals("keep", manager.root[0].title)
        val actions = SingleActionFile("action1_hbtn", 1); assertTrue(actions.load(f.context)); assertEquals(SingleAction.GO_HOME, actions.action.single().id)
        AppPrefs.load(f.context)
        assertEquals(2, AppPrefs.webTheme.get().toInt()); assertEquals(SearchTemplates.DEFAULT_GOOGLE_URL, AppPrefs.search_url.get())
    }

    @Test fun bothDialZipAndRawDatabasePreserveExistingUrlsAndMergeOnlyNewRows() = fixture { f ->
        val manager = SpeedDialManager(f.context)
        manager.update(SpeedDial("https://keep.invalid/", "current", null, false))
        val input = f.dialDatabase()
        for ((index, file) in listOf(input, f.zip(mapOf("speeddial1.db" to input.readBytes()))).withIndex()) {
            PortableImport.prepare(f.context, Uri.fromFile(file)).use { plan ->
                val result = plan.apply(setOf(ImportCategory.SpeedDial), "unused")
                assertEquals(if(index == 0) 1 else 0, result.speedDialAdded)
                assertEquals(if(index == 0) 1 else 2, result.speedDialDuplicates)
            }
        }
        val saved = manager.all
        assertEquals(2, saved.size); assertEquals("current", saved.first { it.url == "https://keep.invalid/" }.title)
        assertEquals("from-source", saved.first { it.url == "https://new.invalid/" }.title)
    }

    @Test fun realDatabaseFailureAfterOtherWritesRollsBackSettingsBookmarksActionsAndDialRows() = fixture { f ->
        val prefs = AppPrefs.getPreference(f.context); prefs.edit().putBoolean("javascript", true).commit()
        val bookmarks = BookmarkManager.getInstance(f.context)
        bookmarks.root.add(BookmarkSite("keep", "https://keep.invalid/", 100)); assertTrue(bookmarks.save())
        val before = bookmarks.file.readBytes()
        val manager = SpeedDialManager(f.context); manager.update(SpeedDial("https://keep.invalid/", "keep", null, false))
        // fixture の補助tableだけを壊し、行追加とファイル・prefs保存後の失敗を実際に起こす。
        SQLiteDatabase.openDatabase(f.context.getDatabasePath(SpeedDialManager.DB_NAME).path, null, SQLiteDatabase.OPEN_READWRITE).use { it.execSQL("DROP TABLE info") }
        val input = f.zip(mapOf(ImportArchive.PREFERENCES to "<map><boolean name='javascript' value='false'/></map>".toByteArray(),
            ImportArchive.BOOKMARK to """[{"0":2,"1":"new","2":"https://new.invalid/"}]""".toByteArray(),
            "app_action1_hbtn/1.dat" to "[[1020,null]]".toByteArray(), ImportArchive.DIAL to f.dialDatabase().readBytes()))
        PortableImport.prepare(f.context, Uri.fromFile(input)).use { plan ->
            f.context.failNextPreferenceCommit = true
            assertThrows(Exception::class.java) { plan.apply(plan.preview.categories, "imported") }
            assertFalse(plan.recoveryRequired)
        }
        assertTrue(prefs.getBoolean("javascript", false)); assertArrayEquals(before, bookmarks.file.readBytes())
        assertEquals(1, bookmarks.root.size()); assertEquals("keep", bookmarks.root[0].title)
        assertFalse(File(f.context.getDir("action1_hbtn", 0), "1.dat").exists())
        assertEquals(listOf("https://keep.invalid/"), manager.all.map { it.url })
    }

    @Test fun interruptedAtomicBookmarkAndActionWritesRecoverAndBadInputKeepsLiveBookmarks() = fixture { f ->
        val manager = BookmarkManager.getInstance(f.context)
        manager.root.add(BookmarkSite("keep", "https://keep.invalid/", 100)); assertTrue(manager.save())
        val before = manager.file.readBytes(); File(manager.file.path + ".bak").writeBytes(before); manager.file.writeText("partial")
        assertTrue(manager.load()); assertArrayEquals(before, manager.file.readBytes()); assertEquals("keep", manager.root[0].title)
        manager.file.writeText("broken"); assertFalse(manager.load()); assertEquals("keep", manager.root[0].title)
        val action = SingleActionFile("action1_hbtn", 1); val file = action.getFile(f.context)
        File(file.path + ".bak").writeText("[[1020,null]]"); file.writeText("partial")
        assertTrue(action.load(f.context)); assertEquals(SingleAction.GO_HOME, action.action.single().id)
        assertFalse(File(file.path + ".bak").exists())
    }

    @Test fun sqliteViewsAndOversizedImagesAreRejectedBeforeApplyingAnyRows() = fixture { f ->
        val view = File(f.context.cacheDir, "view.db")
        SQLiteDatabase.openOrCreateDatabase(view, null).use { db ->
            db.version = 3; db.execSQL("CREATE TABLE source(_id INTEGER,url TEXT,title TEXT,item_order INTEGER,icon BLOB,favicon INTEGER)")
            db.execSQL("CREATE VIEW main_table AS SELECT * FROM source")
        }
        assertThrows(IOException::class.java) { SpeedDialImport.read(view) }
        val database = f.dialDatabase(iconSize = 1024)
        assertThrows(IOException::class.java) { SpeedDialImport.read(database) }
    }

    @Test fun selectedSettingsScriptsGesturesAndBookmarkReferencesMergeWithFreshIdsAndPreserveExistingData() = fixture { f ->
        val manager = BookmarkManager.getInstance(f.context)
        manager.root.add(BookmarkFolder("keep-folder", manager.root, 100)); assertTrue(manager.save())
        val oldCode = "// ==UserScript==\n// @name keep\n// @namespace fixture\n// ==/UserScript==\nold();"
        val importedCode = "// ==UserScript==\n// @name new\n// @namespace fixture\n// ==/UserScript==\nnewScript();"
        UserScriptDatabase(f.context).use { helper ->
            helper.writableDatabase.execSQL("INSERT INTO main_table1(_id,data,enabled) VALUES(1,?,0)", arrayOf(oldCode))
        }
        val sourceScripts = File(f.context.cacheDir, "source-scripts.db")
        SQLiteDatabase.openOrCreateDatabase(sourceScripts, null).use { db ->
            db.version = 2; db.execSQL("CREATE TABLE main_table1(_id INTEGER PRIMARY KEY,data TEXT NOT NULL,enabled INTEGER DEFAULT 1)")
            db.execSQL("INSERT INTO main_table1 VALUES(1,?,0)", arrayOf(importedCode))
            db.execSQL("INSERT INTO main_table1 VALUES(2,?,1)", arrayOf(oldCode.replace("old();", "changed();")))
        }
        val gestureRows = File(f.context.cacheDir, "source-gesture.db")
        SQLiteDatabase.openOrCreateDatabase(gestureRows, null).use { db ->
            db.version = 1; db.execSQL("CREATE TABLE main_table1(_id INTEGER PRIMARY KEY,action TEXT NOT NULL)")
            db.execSQL("INSERT INTO main_table1 VALUES(1,'[[1020,null]]')")
        }
        val sample = ImportGestureFormat.Sample(9, listOf(ImportGestureFormat.Stroke(listOf(
            ImportGestureFormat.Point(1f, 2f, 0), ImportGestureFormat.Point(3f, 4f, 1)))))
        val library = ImportGestureFormat.encode(listOf(ImportGestureFormat.Entry(1, listOf(sample))))
        val bookmarkIntent = Intent().setComponent(ComponentName("io.github.ajichama.citrusbrowser", BookmarkActivity::class.java.name)).putExtra("id", 100L)
        val scriptIntent = Intent().setComponent(ComponentName("io.github.ajichama.citrusbrowser", UserScriptEditActivity::class.java.name))
            .putExtra(UserScriptEditActivity.EXTRA_USERSCRIPT, 1L)
        val input = f.zip(mapOf(ImportArchive.PREFERENCES to "<map><long name='save_bookmark_folder_id' value='100'/><boolean name='userjs_enable' value='true'/><string name='theme_setting'>theme://internal/light</string></map>".toByteArray(),
            ImportArchive.BOOKMARK to """[{"0":1,"1":"source-folder","3":100,"2":[]}]""".toByteArray(),
            ImportScript.PATH to sourceScripts.readBytes(), "app_action1_hbtn/1.dat" to intentAction(bookmarkIntent),
            "app_action1_hbtn/2.dat" to intentAction(scriptIntent), "app_gestures1/0.lib.dat" to library,
            "app_gestures1/0.list.dat" to gestureRows.readBytes(), ImportGestures.MULTI to """[{"0":2,"1":[1,4],"2":[[1020,null]]}]""".toByteArray(),
            ImportUserSettings.USER_AGENTS to """[{"1":"fixture UA","0":"fixture-agent"}]""".toByteArray(),
            SearchImport.PATH to """{"sel":7,"idc":8,"item":[{"3":7,"0":"fixture search","1":"https://search.invalid/?q=%s","2":42,"4":false}]}""".toByteArray()))
        PortableImport.prepare(f.context, Uri.fromFile(input)).use { plan ->
            assertEquals(setOf(ImportCategory.Settings, ImportCategory.Bookmarks, ImportCategory.Scripts, ImportCategory.Gestures), plan.preview.categories)
            val result = plan.apply(plan.preview.categories, "imported")
            assertEquals(1, result.scriptsAdded); assertEquals(2, result.gesturesAdded); assertTrue(result.preserved >= 1)
        }
        assertEquals("keep-folder", manager.root[0].title)
        val importedFolder = (manager.root[1] as BookmarkFolder)[0]
        assertNotEquals(100L, importedFolder.id)
        val prefs = AppPrefs.getPreference(f.context)
        assertEquals(importedFolder.id, prefs.getLong("save_bookmark_folder_id", -1)); assertTrue(prefs.getBoolean("userjs_enable", false))
        assertEquals("theme://internal/light", prefs.getString("theme_setting", null))
        assertEquals(importedFolder.id, savedIntent(f.context, 1).getLongExtra("id", -1))
        assertEquals(f.context.packageName, savedIntent(f.context, 1).component!!.packageName)
        val savedAction = JsonReader.of(Buffer().write(File(f.context.getDir("action1_hbtn", 0), "1.dat").readBytes())).use { it.readJsonValue() as List<*> }
        assertEquals("fixture #Intent; title", ((savedAction.single() as List<*>)[1] as Map<*, *>)["1"])
        val newScriptId = savedIntent(f.context, 2).getLongExtra(UserScriptEditActivity.EXTRA_USERSCRIPT, -1)
        assertNotEquals(1L, newScriptId)
        SQLiteDatabase.openDatabase(f.context.getDatabasePath("userjs1.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT data,enabled FROM main_table1 WHERE _id=1", null).use { c -> assertTrue(c.moveToFirst()); assertEquals(oldCode, c.getString(0)); assertEquals(0, c.getInt(1)) }
            db.rawQuery("SELECT data,enabled FROM main_table1 WHERE _id=?", arrayOf(newScriptId.toString())).use { c -> assertTrue(c.moveToFirst()); assertEquals(importedCode, c.getString(0)); assertEquals(0, c.getInt(1)) }
        }
        val loaded = ImportGestureFormat.decode(File(f.context.getDir("gestures1", 0), "0.lib.dat").readBytes())
        SQLiteDatabase.openDatabase(File(f.context.getDir("gestures1", 0), "0.list.dat").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT action FROM main_table1 WHERE _id=?", arrayOf(loaded.single().actionId.toString())).use { c -> assertTrue(c.moveToFirst()); assertEquals("[[1020,null]]", c.getString(0)) }
        }
        val search = SearchImport.decode(File(f.context.filesDir, "searchUrl.json").readBytes(), Moshi.Builder().build())
        assertEquals(7, search.selectedId); assertEquals("fixture search", search.items.single().title)
    }

    @Test fun unselectedDependentDataDoesNotBindOldIdsToExistingRowsOrReplaceActions() = fixture { f ->
        val prefs = AppPrefs.getPreference(f.context)
        prefs.edit().putLong("save_bookmark_folder_id", 3).putBoolean("userjs_enable", false).commit()
        val actionFile = File(f.context.getDir("action1_hbtn", 0), "1.dat").apply { writeText("[[1020,null]]") }
        val intent = Intent().setComponent(ComponentName("io.github.ajichama.citrusbrowser", BookmarkActivity::class.java.name)).putExtra("id", 100L)
        val input = f.zip(mapOf(ImportArchive.PREFERENCES to "<map><long name='save_bookmark_folder_id' value='100'/><boolean name='userjs_enable' value='true'/></map>".toByteArray(),
            "app_action1_hbtn/1.dat" to intentAction(intent)))
        PortableImport.prepare(f.context, Uri.fromFile(input)).use { plan ->
            val result = plan.apply(setOf(ImportCategory.Settings), "unused")
            assertTrue(result.ignored >= 3)
        }
        assertEquals(3L, prefs.getLong("save_bookmark_folder_id", -1)); assertFalse(prefs.getBoolean("userjs_enable", true))
        assertEquals("[[1020,null]]", actionFile.readText())
        val external = Intent().setComponent(ComponentName("external.fixture", "external.fixture.BookmarkActivity")).putExtra("id", 100L)
        val remapped = ImportIntentReferences(f.context.packageName, mapOf(100L to 200L), emptyMap()).remap(external.toUri(Intent.URI_INTENT_SCHEME))!!
        assertEquals(100L, Intent.parseUri(remapped, Intent.URI_INTENT_SCHEME).getLongExtra("id", -1))
        val selector = Intent(Intent.ACTION_MAIN).apply {
            setSelector(Intent().setComponent(ComponentName("io.github.ajichama.citrusbrowser", BookmarkActivity::class.java.name)).putExtra("id", 100L))
        }
        assertNull(ImportIntentReferences(f.context.packageName, emptyMap(), emptyMap()).remap(selector.toUri(Intent.URI_INTENT_SCHEME)))
        val selectorResult = ImportIntentReferences(f.context.packageName, mapOf(100L to 200L), emptyMap()).remap(selector.toUri(Intent.URI_INTENT_SCHEME))!!
        assertEquals(200L, Intent.parseUri(selectorResult, Intent.URI_INTENT_SCHEME).selector!!.getLongExtra("id", -1))
        val debugSource = Intent().setComponent(ComponentName("io.github.ajichama.citrusbrowser.debug", BookmarkActivity::class.java.name)).putExtra("id", 100L)
        val releaseResult = ImportIntentReferences("io.github.ajichama.citrusbrowser", mapOf(100L to 201L), emptyMap())
            .remap(debugSource.toUri(Intent.URI_INTENT_SCHEME))!!
        assertEquals("io.github.ajichama.citrusbrowser", Intent.parseUri(releaseResult, Intent.URI_INTENT_SCHEME).component!!.packageName)
        assertEquals(201L, Intent.parseUri(releaseResult, Intent.URI_INTENT_SCHEME).getLongExtra("id", -1))
    }

    @Test fun corruptCurrentSearchSettingsAbortWithoutReplacingAnyOtherConfiguration() = fixture { f ->
        val current = File(f.context.filesDir, "searchUrl.json").apply { writeText("broken-current") }
        val prefs = AppPrefs.getPreference(f.context); prefs.edit().putBoolean("javascript", true).commit()
        val input = f.zip(mapOf(ImportArchive.PREFERENCES to "<map><boolean name='javascript' value='false'/></map>".toByteArray(),
            SearchImport.PATH to """{"sel":1,"idc":2,"item":[{"3":1,"0":"source","1":"https://search.invalid/?q=%s","2":1,"4":false}]}""".toByteArray()))
        PortableImport.prepare(f.context, Uri.fromFile(input)).use { plan -> assertThrows(Exception::class.java) { plan.apply(setOf(ImportCategory.Settings), "unused") } }
        assertEquals("broken-current", current.readText()); assertTrue(prefs.getBoolean("javascript", false))
    }

    @Test fun preferenceRollbackPreservesAConcurrentWriterAndRetainsRecoveryCopies() = fixture { f ->
        val prefs = AppPrefs.getPreference(f.context)
        prefs.edit().putString("user_agent", "before").commit()
        val input = f.zip(mapOf(ImportArchive.PREFERENCES to "<map><string name='user_agent'>mine</string></map>".toByteArray()))
        PortableImport.prepare(f.context, Uri.fromFile(input)).use { plan ->
            f.context.failNextPreferenceCommit = true
            f.context.concurrentUserAgentOnFailure = "after"
            assertThrows(Exception::class.java) { plan.apply(setOf(ImportCategory.Settings), "unused") }
            assertTrue(plan.recoveryRequired)
        }
        assertEquals("after", prefs.getString("user_agent", null))
        assertTrue(f.context.filesDir.listFiles()!!.any { it.name.startsWith("import-recovery-") })
    }

    private fun intentAction(intent: Intent): ByteArray = Buffer().also { buffer -> JsonWriter.of(buffer).use { writer ->
        writer.beginArray().beginArray().value(SingleAction.START_ACTIVITY).beginObject().name("0").value(intent.toUri(Intent.URI_INTENT_SCHEME))
            .name("1").value("fixture #Intent; title").endObject().endArray().endArray()
    } }.readByteArray()
    private fun savedIntent(context: Context, id: Int): Intent {
        val bytes = File(context.getDir("action1_hbtn", 0), "$id.dat").readBytes()
        val list = JsonReader.of(Buffer().write(bytes)).use { it.readJsonValue() as List<*> }
        val data = (list.single() as List<*>)[1] as Map<*, *>
        return Intent.parseUri(data["0"] as String, 0)
    }
    private fun fixture(test: (Fixture) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val root = File.createTempFile("portable-import-test-", ".dir", base.cacheDir)
        check(root.delete() && root.mkdir()); check(root.canonicalFile.parentFile == base.cacheDir.canonicalFile)
        val context = FixtureContext(base, root)
        // 本番フックを追加せず、自己targetテスト内のsingletonだけをfixtureへ分離・復元する。
        val bookmark = BookmarkManager::class.java.declaredFields.single { it.type == BookmarkManager::class.java && Modifier.isStatic(it.modifiers) }.apply { isAccessible = true }
        val helperClass = SpeedDialManager::class.java.declaredClasses.single { SQLiteOpenHelper::class.java.isAssignableFrom(it) }
        val helper = helperClass.declaredFields.single { it.type == helperClass && Modifier.isStatic(it.modifiers) }.apply { isAccessible = true }
        val priorBookmark = bookmark.get(null); val priorHelper = helper.get(null)
        bookmark.set(null, null); helper.set(null, null)
        try { test(Fixture(context)) }
        finally {
            (helper.get(null) as? SQLiteOpenHelper)?.close(); helper.set(null, priorHelper); bookmark.set(null, priorBookmark)
            context.prefsNames.forEach { name -> AtomicFile(File(base.applicationInfo.dataDir, "shared_prefs/$name.xml")).delete() }
            check(root.deleteRecursively())
        }
    }

    private class Fixture(val context: FixtureContext) {
        fun zip(entries: Map<String, ByteArray>): File = File.createTempFile("export-", ".zip", context.cacheDir).also { file ->
            ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
        }
        fun dialDatabase(iconSize: Int = 8): File {
            val file = File.createTempFile("dial-", ".db", context.cacheDir)
            val bitmap = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)
            val icon = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray(); bitmap.recycle()
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.version = 1
                db.execSQL("CREATE TABLE main_table (_id INTEGER PRIMARY KEY,url TEXT NOT NULL,title TEXT,item_order INTEGER,icon BLOB,favicon INTEGER)")
                db.execSQL("INSERT INTO main_table VALUES(1,?,?,?,?,?)", arrayOf("https://keep.invalid/", "source-copy", 0, icon, 0))
                db.execSQL("INSERT INTO main_table VALUES(2,?,?,?,?,?)", arrayOf("https://new.invalid/", "from-source", 1, icon, 1))
            }
            return file
        }
    }
    private class FixtureContext(base: Context, val root: File) : ContextWrapper(base) {
        var failNextPreferenceCommit = false
        var concurrentUserAgentOnFailure: String? = null
        val prefsNames = hashSetOf<String>(); private val prefix = "portable-import-test-${UUID.randomUUID()}-"
        override fun getApplicationContext(): Context = this
        override fun getApplicationInfo() = ApplicationInfo(super.getApplicationInfo()).apply { dataDir = root.path }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getFileStreamPath(name: String) = File(filesDir, name)
        override fun getDir(name: String, mode: Int) = File(root, "app_$name").apply { mkdirs() }
        override fun getDatabasePath(name: String) = (if (name.startsWith(File.separator)) File(name) else File(root, "databases/$name")).also { check(it.canonicalPath.startsWith(root.canonicalPath + File.separator)); it.parentFile!!.mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            prefsNames += prefix + name
            val prefs = super.getSharedPreferences(prefix + name, mode)
            return object : SharedPreferences by prefs {
                override fun edit(): SharedPreferences.Editor {
                    val editor = prefs.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun commit(): Boolean {
                            val result = editor.commit()
                            return if (failNextPreferenceCommit) {
                                failNextPreferenceCommit = false
                                concurrentUserAgentOnFailure?.let { prefs.edit().putString("user_agent", it).commit() }
                                concurrentUserAgentOnFailure = null
                                false
                            } else result
                        }
                    }
                }
            }
        }
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?) = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, handler: DatabaseErrorHandler?) = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, handler)
    }
}
