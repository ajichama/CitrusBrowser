package io.github.ajichama.citrusbrowser.search.domain.usecase

import io.github.ajichama.citrusbrowser.search.domain.ISearchUrlRepository
import io.github.ajichama.citrusbrowser.search.model.provider.SearchSettings
import io.github.ajichama.citrusbrowser.search.model.provider.SearchUrl
import org.junit.Assert.*
import org.junit.Test

class SearchSettingsLifecycleTest {
    private fun item(id: Int, name: String) = SearchUrl(id, name, "https://example.org/$name?q=%s", 1)
    private fun SearchSettings.snapshot() = copy(items = items.map { it.copy() })

    private inner class Repository(initial: SearchSettings) : ISearchUrlRepository {
        var stored = initial.snapshot()
        var attempts = 0
        var fail = false
        override fun load() = stored.snapshot()
        override fun save(settings: SearchSettings): Boolean {
            attempts++
            if (fail) return false
            stored = settings.snapshot()
            return true
        }
    }

    @Test fun pausedEditorDoesNotResaveItsOldListAfterImportAndReloadsOnResume() {
        val repository = Repository(SearchSettings(7, 7, listOf(item(7, "Original"))))
        val editor = SearchSettingsViewUseCase(repository)
        editor[0] = item(7, "Edited")
        assertTrue(editor.commitIfNeed())
        repository.stored = repository.stored.copy(items = repository.stored.items + item(9, "Imported"))
        val imported = repository.stored.snapshot()
        assertTrue(editor.commitIfNeed())
        assertEquals(1, repository.attempts)
        assertEquals(imported, repository.stored)
        assertTrue(editor.reloadIfClean())
        assertEquals(listOf("Edited", "Imported"), editor.list.map { it.title })
        assertFalse(editor.reloadIfClean())
        assertTrue(editor.commitIfNeed())
        assertEquals(1, repository.attempts)
    }

    @Test fun failedSaveRetainsDirtyEditsAndCanRetryWithoutReloadingOlderDiskState() {
        val repository = Repository(SearchSettings(7, 7, listOf(item(7, "Original"))))
        val editor = SearchSettingsViewUseCase(repository)
        editor[0] = item(7, "Unsaved")
        repository.fail = true
        assertFalse(editor.commitIfNeed())
        assertFalse(editor.reloadIfClean())
        assertEquals("Unsaved", editor.list.single().title)
        assertEquals("Original", repository.stored.items.single().title)
        repository.fail = false
        assertTrue(editor.commitIfNeed())
        assertEquals("Unsaved", repository.stored.items.single().title)
        assertTrue(editor.commitIfNeed())
        assertEquals(2, repository.attempts)
    }

    @Test fun unsavedEditorNeverOverwritesAnExternalImportAfterIoFailure() {
        val repository = Repository(SearchSettings(7, 7, listOf(item(7, "Original"))))
        val editor = SearchSettingsViewUseCase(repository)
        editor[0] = item(7, "Unsaved")
        repository.fail = true
        assertFalse(editor.commitIfNeed())
        repository.stored = repository.stored.copy(items = repository.stored.items + item(9, "Imported"))
        val imported = repository.stored.snapshot()
        repository.fail = false
        assertFalse(editor.reloadIfClean())
        assertFalse(editor.commitIfNeed())
        assertEquals(imported, repository.stored)
        assertEquals("Unsaved", editor.list.single().title)
        assertEquals(1, repository.attempts)
    }

    @Test fun normalAddAllocatesStableIdsAndUndoPreservesTheRemovedId() {
        val repository = Repository(SearchSettings(7, 1, listOf(item(50, "A"), item(7, "B"))))
        val editor = SearchSettingsViewUseCase(repository)
        editor.add(item(-1, "New"))
        assertEquals(51, editor.list.last().id)
        val removed = editor.removeAt(2)
        editor.add(1, removed)
        editor.add(item(-1, "Next"))
        assertTrue(editor.commitIfNeed())
        assertEquals(listOf(50, 51, 7, 52), repository.stored.items.map { it.id })
        assertEquals(7, repository.stored.selectedId)
        assertEquals(52, repository.stored.idCount)
    }
}
