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

package io.github.ajichama.citrusbrowser

import android.os.SystemClock
import android.util.Log
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ajichama.citrusbrowser.legacy.settings.activity.MainSettingsActivity
import io.github.ajichama.citrusbrowser.legacy.settings.activity.MainSettingsFragment
import io.github.ajichama.citrusbrowser.ui.settings.AppPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import io.github.ajichama.citrusbrowser.legacy.R as LegacyR

/**
 * 設定のすべての画面（トップの各画面と、その中の入れ子の画面）が、落ちずに開いて項目を表示することを確かめる。
 * 利用者が押したときと同じ入口（トップの項目の画面と onPreferenceStartScreen）で開き、開くだけで設定を変えない。
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreensSmokeTest {
    @Test(timeout = 180_000)
    fun everySettingsScreenOpensWithoutCrashing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val before = preferences(context)
        val opened = mutableListOf<String>()
        ActivityScenario.launch(MainSettingsActivity::class.java).use { scenario ->
            var top = awaitScreen(scenario, previous = null, label = "トップ")
            for ((key, _) in MainSettingsFragment.SCREENS) {
                scenario.onActivity { it.replaceFragment(requireNotNull(MainSettingsFragment.createScreen(key)), key) }
                val screen = awaitScreen(scenario, previous = top, label = key)
                opened += key
                openNestedScreens(scenario, screen, key, opened)
                top = back(scenario, label = key)
            }
        }
        Log.i(TAG, "開いた設定の画面: ${opened.size} 件: $opened")
        assertTrue("入れ子の画面を1つも開いていません: $opened", opened.size > MainSettingsFragment.SCREENS.size)
        val after = preferences(context)
        // 値そのものは出さず、変わったキーだけを示す。
        val changed = (before.keys + after.keys).filter { before[it] != after[it] || it in before != it in after }.sorted()
        assertEquals("設定の画面を開くだけで、設定が変わりました", emptyList<String>(), changed)
    }

    /** 今の画面の中の入れ子の画面を順に開き、さらにその中も開く。 */
    private fun openNestedScreens(
        scenario: ActivityScenario<MainSettingsActivity>,
        parent: PreferenceFragmentCompat,
        path: String,
        opened: MutableList<String>,
    ) {
        val keys = onMain(scenario) {
            val (external, screens) = nestedScreens(parent.preferenceScreen).filter { it.isEnabled && it.isVisible }
                // 押したときの処理を持つものは、その処理（別の Activity など）が開くので、ここでは開かない（利用者の操作と同じ）。
                .partition { it.onPreferenceClickListener != null }
            if (external.isNotEmpty()) Log.i(TAG, "別の画面で開く項目（対象外）: $path › ${external.map { it.key }}")
            screens.map { requireNotNull(it.key) { "キーのない入れ子の画面: $path › ${it.title}" } }
        }
        var current = parent
        for (key in keys) {
            val label = "$path › $key"
            onMain(scenario) { activity ->
                val pref = requireNotNull(current.findPreference<PreferenceScreen>(key)) { "画面がありません: $label" }
                assertTrue("画面を開けません: $label", activity.onPreferenceStartScreen(current, pref))
            }
            val child = awaitScreen(scenario, previous = current, label = label)
            opened += label
            openNestedScreens(scenario, child, label, opened)
            current = back(scenario, label)
        }
    }

    /** 戻って、前の画面が表示されるのを待つ。 */
    private fun back(scenario: ActivityScenario<MainSettingsActivity>, label: String): PreferenceFragmentCompat {
        val leaving = onMain(scenario) { activity ->
            val leaving = activity.supportFragmentManager.findFragmentById(LegacyR.id.container)
            assertTrue("戻れません: $label", activity.supportFragmentManager.popBackStackImmediate())
            leaving
        }
        return awaitScreen(scenario, previous = leaving, label = "$label から戻る")
    }

    /** 画面が入れ替わり、項目が表示される（RecyclerView に行が並ぶ）まで待つ。 */
    private fun awaitScreen(
        scenario: ActivityScenario<MainSettingsActivity>,
        previous: Fragment?,
        label: String,
    ): PreferenceFragmentCompat {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val ready = onMain(scenario) { activity ->
                val fragment = activity.supportFragmentManager.findFragmentById(LegacyR.id.container)
                (fragment as? PreferenceFragmentCompat)?.takeIf {
                    it !== previous && it.isResumed && it.view != null && (it.listView?.childCount ?: 0) > 0
                }
            }
            if (ready != null) return ready
            Thread.sleep(50)
        }
        throw AssertionError("設定の画面が表示されません: $label")
    }

    /** group の中の入れ子の画面（PreferenceScreen）。入れ子の画面の中身は、開いた先で調べる。 */
    private fun nestedScreens(group: PreferenceGroup): List<PreferenceScreen> =
        (0 until group.preferenceCount).map(group::getPreference).flatMap { pref ->
            when (pref) {
                is PreferenceScreen -> listOf(pref)
                is PreferenceGroup -> nestedScreens(pref)
                else -> emptyList()
            }
        }

    private fun <T> onMain(scenario: ActivityScenario<MainSettingsActivity>, action: (MainSettingsActivity) -> T): T {
        var result: Result<T>? = null
        scenario.onActivity { result = runCatching { action(it) } }
        return requireNotNull(result).getOrThrow()
    }

    private fun preferences(context: android.content.Context): Map<String, Any?> =
        AppPrefs.getPreference(context).all.mapValues { (_, value) -> if (value is Set<*>) value.toSet() else value }

    private companion object {
        const val TAG = "SettingsScreensSmoke"
    }
}
