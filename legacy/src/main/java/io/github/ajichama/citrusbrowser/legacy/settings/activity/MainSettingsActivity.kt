/*
 * Copyright (C) 2017-2019 Hazuki
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

package io.github.ajichama.citrusbrowser.legacy.settings.activity

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.util.TypedValue
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.BlendModeColorFilterCompat
import androidx.core.graphics.BlendModeCompat
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import io.github.ajichama.citrusbrowser.core.utility.extensions.getResColor
import io.github.ajichama.citrusbrowser.legacy.R
import io.github.ajichama.citrusbrowser.legacy.debug.DebugActivity
import io.github.ajichama.citrusbrowser.legacy.settings.search.SettingsSearchEntry
import io.github.ajichama.citrusbrowser.legacy.settings.search.SettingsSearchFragment
import io.github.ajichama.citrusbrowser.ui.app.ThemeActivity

@AndroidEntryPoint
class MainSettingsActivity : ThemeActivity(), PreferenceFragmentCompat.OnPreferenceStartScreenCallback, ReplaceFragmentListener,
    SettingsSearchFragment.Host {

    /** 検索の結果へ移る途中で、これから押す項目のキー（#25）。移っていないときは null。 */
    private var pendingClicks: ArrayDeque<String>? = null
    private var pendingTarget: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Intent.ACTION_CREATE_SHORTCUT == intent?.action) {
            @Suppress("DEPRECATION")
            setResult(Activity.RESULT_OK, Intent().apply {
                putExtra(Intent.EXTRA_SHORTCUT_INTENT, Intent(this@MainSettingsActivity, MainSettingsActivity::class.java))
                putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE, Intent.ShortcutIconResource.fromContext(applicationContext, R.mipmap.ic_launcher))
                putExtra(Intent.EXTRA_SHORTCUT_NAME, getString(R.string.browser_settings))
            })
            finish()
            return
        }

        setContentView(R.layout.activity_settings)
        setupActionBar()

        // 検索の結果へ移る途中なら、画面が開くたびに次の項目を押し、最後に目的の項目を示す。
        supportFragmentManager.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
                if (f is PreferenceFragmentCompat && pendingClicks != null) v.post { continueSearchNavigation(f) }
            }
        }, false)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, MainSettingsFragment())
                .commit()
        }
    }

    /**
     * Set up the [android.app.ActionBar], if the API is available.
     */
    private fun setupActionBar() {
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setBackgroundDrawable(ColorDrawable(getResColor(R.color.primary)))
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // 設定の検索（#25）。どの設定画面からでも開ける。
        menu.add(Menu.NONE, R.id.searchSettings, Menu.NONE, R.string.search_settings).apply {
            // 白いアイコンなので、上のバーの配色に合わせて塗る（明るいテーマでも見えるように）。
            icon = AppCompatResources.getDrawable(this@MainSettingsActivity, R.drawable.ic_search_white_24dp)?.mutate()?.apply {
                colorFilter = BlendModeColorFilterCompat.createBlendModeColorFilterCompat(
                    getResColor(R.color.actionBarIconColor), BlendModeCompat.SRC_ATOP)
            }
            setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }
        menu.add("Debug mode").intent = Intent(this, DebugActivity::class.java)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                return true
            }
            R.id.searchSettings -> {
                if (supportFragmentManager.findFragmentById(R.id.container) !is SettingsSearchFragment) {
                    replaceFragment(SettingsSearchFragment(), "settings_search")
                }
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun openSettingsSearchResult(entry: SettingsSearchEntry) {
        val screen = MainSettingsFragment.createScreen(entry.topKey) ?: return
        pendingClicks = ArrayDeque(entry.clickKeys)
        pendingTarget = entry.key
        replaceFragment(screen, entry.topKey)
    }

    private fun continueSearchNavigation(fragment: PreferenceFragmentCompat) {
        val clicks = pendingClicks ?: return
        if (!fragment.isAdded) return
        val next = clicks.removeFirstOrNull()
        if (next != null) {
            // 利用者が押したときと同じ入口で、次の画面を開く。開けなければ、今の画面で止める。
            val pref = fragment.findPreference<Preference>(next)
            val opened = when {
                pref == null || !pref.isEnabled -> false
                pref is PreferenceScreen -> onPreferenceStartScreen(fragment, pref)
                else -> pref.onPreferenceClickListener?.onPreferenceClick(pref) == true
            }
            if (!opened) clearSearchNavigation()
            return
        }
        val target = pendingTarget
        clearSearchNavigation()
        if (target != null) highlightPreference(fragment, target)
    }

    private fun clearSearchNavigation() {
        pendingClicks = null
        pendingTarget = null
    }

    /** 目的の項目までスクロールし、アクセントの色を薄く敷いてしばらく示す。 */
    private fun highlightPreference(fragment: PreferenceFragmentCompat, key: String) {
        if (fragment.findPreference<Preference>(key) == null) return
        fragment.scrollToPreference(key)
        val list = fragment.listView ?: return
        list.postDelayed({
            val callback = list.adapter as? PreferenceGroup.PreferencePositionCallback ?: return@postDelayed
            val position = callback.getPreferenceAdapterPosition(key)
            if (position == RecyclerView.NO_POSITION) return@postDelayed
            val view = list.findViewHolderForAdapterPosition(position)?.itemView ?: return@postDelayed
            val accent = TypedValue().also { theme.resolveAttribute(androidx.appcompat.R.attr.colorAccent, it, true) }.data
            // 項目の背景（押したときの表示）は変えず、前景に重ねて、時間がたったら外す。
            view.foreground = ColorDrawable(ColorUtils.setAlphaComponent(accent, HIGHLIGHT_ALPHA))
            view.postDelayed({ view.foreground = null }, HIGHLIGHT_MILLIS)
        }, SCROLL_WAIT_MILLIS)
    }

    companion object {
        private const val SCROLL_WAIT_MILLIS = 300L
        private const val HIGHLIGHT_MILLIS = 1500L
        private const val HIGHLIGHT_ALPHA = 0x48
    }

    override fun onPreferenceStartScreen(caller: PreferenceFragmentCompat, pref: PreferenceScreen): Boolean {
        return if (caller is CitrusPreferenceFragment) {
            if (!caller.onPreferenceStartScreen(pref)) {
                replaceFragment(PreferenceScreenFragment.newInstance(caller.preferenceResId, pref.key), pref.key)
            }
            true
        } else {
            false
        }
    }

    override fun replaceFragment(fragment: androidx.fragment.app.Fragment, key: String) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
            .addToBackStack(key)
            .commit()
    }
}
