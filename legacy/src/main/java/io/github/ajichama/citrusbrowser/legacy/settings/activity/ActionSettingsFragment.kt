/*
 * Copyright (C) 2017-2020 Hazuki
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

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import io.github.ajichama.citrusbrowser.bookmark.overflow.MenuType
import io.github.ajichama.citrusbrowser.bookmark.overflow.view.BookmarkOverflowMenuActivity
import io.github.ajichama.citrusbrowser.legacy.R
import io.github.ajichama.citrusbrowser.legacy.action.manager.QuickControlActionManager
import io.github.ajichama.citrusbrowser.legacy.settings.preference.ActionListPreference
import io.github.ajichama.citrusbrowser.legacy.gesture.multiFinger.MultiFingerSettingsActivity

class ActionSettingsFragment : CitrusPreferenceFragment() {

    private var replaceFragment: ReplaceFragmentListener? = null

    override fun onCreateCitrusPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_action_settings)

        findPreference<Preference>("mf_gesture")!!.setOnPreferenceClickListener {
            startActivity(Intent(activity, MultiFingerSettingsActivity::class.java))
            true
        }
    }

    override fun onPreferenceStartScreen(pref: PreferenceScreen): Boolean {
        if (pref.key == "ps_bookmark") {
            replaceFragment?.replaceFragment(BookmarkPreferenceScreen(), "ps_bookmark")
            return true
        }
        if (pref.key == "ps_qc") {
            replaceFragment?.replaceFragment(QuickControlPreferenceScreen(), "ps_qc")
            return true
        }
        return super.onPreferenceStartScreen(pref)
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)

        replaceFragment = activity as ReplaceFragmentListener
    }

    override fun onDetach() {
        super.onDetach()

        replaceFragment = null
    }

    /** クイックコントロールの設定。項目が空のまま有効にしたら、既定の項目か自分での設定かを選ばせる（#36） */
    class QuickControlPreferenceScreen : CitrusPreferenceFragment() {

        override fun onCreateCitrusPreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.pref_action_settings, "ps_qc")

            findPreference<Preference>("qc_enable")!!.setOnPreferenceChangeListener { _, newValue ->
                if (newValue == true && QuickControlActionManager.getInstance(requireContext()).isEmpty) {
                    showEmptyItemsDialog()
                }
                true
            }
        }

        private fun showEmptyItemsDialog() {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.qc_empty_title)
                .setMessage(R.string.qc_empty_message)
                .setPositiveButton(R.string.qc_empty_use_default) { _, _ ->
                    val context = requireContext()
                    QuickControlActionManager.getInstance(context).apply {
                        setDefaultItems()
                        save(context)
                    }
                    Toast.makeText(context, R.string.qc_default_added, Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton(R.string.qc_empty_customize) { _, _ -> openLevel1Items() }
                .setNegativeButton(R.string.qc_empty_later, null)
                .show()
        }

        /** 画面の最初の項目の一覧（Level 1）の設定を開く */
        private fun openLevel1Items() {
            val screen = preferenceScreen ?: return
            for (i in 0 until screen.preferenceCount) {
                val pref = screen.getPreference(i)
                if (pref is ActionListPreference) {
                    pref.openActionList()
                    return
                }
            }
        }
    }

    class BookmarkPreferenceScreen : CitrusPreferenceFragment() {

        override fun onCreateCitrusPreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.pref_action_settings, "ps_bookmark")

            findPreference<Preference>("bookmark_option_site")!!.setOnPreferenceClickListener {
                startActivity(BookmarkOverflowMenuActivity.createIntent(requireActivity(), MenuType.SITE))
                true
            }
            findPreference<Preference>("bookmark_option_folder")!!.setOnPreferenceClickListener {
                startActivity(BookmarkOverflowMenuActivity.createIntent(requireActivity(), MenuType.FOLDER))
                true
            }
        }
    }
}
