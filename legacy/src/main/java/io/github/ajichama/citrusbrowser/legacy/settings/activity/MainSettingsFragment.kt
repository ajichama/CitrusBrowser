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

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import io.github.ajichama.citrusbrowser.legacy.Constants
import io.github.ajichama.citrusbrowser.legacy.R
import io.github.ajichama.citrusbrowser.legacy.help.BROWSER_HELP_URL

class MainSettingsFragment : CitrusPreferenceFragment() {

    override fun onCreateCitrusPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_main)

        SCREENS.forEach { (key, _) ->
            findPreference<Preference>(key)!!.setOnPreferenceClickListener {
                openFragment(createScreen(key)!!)
                true
            }
        }
        findPreference<Preference>("activity_help")!!.setOnPreferenceClickListener {
            val activity = activity ?: return@setOnPreferenceClickListener true
            val intent = Intent(Constants.intent.ACTION_OPEN_DEFAULT)
            intent.setClassName(activity, Constants.activity.MAIN_BROWSER)
            intent.data = Uri.parse(BROWSER_HELP_URL)
            startActivity(intent)
            true
        }
    }

    companion object {
        /** トップ画面の項目のキーと、開く画面の XML（設定の検索の索引にも使う、#25）。 */
        val SCREENS = listOf(
            "fragment_browser" to R.xml.pref_browser_settings,
            "fragment_page" to R.xml.pref_page_settings,
            "fragment_privacy" to R.xml.pref_privacy,
            "fragment_ui" to R.xml.pref_ui_settings,
            "fragment_action" to R.xml.pref_action_settings,
            "fragment_speseddial" to R.xml.pref_speeddial,
            "fragment_application" to R.xml.pref_app_settings,
            "fragment_import_export" to R.xml.pref_import_export,
            "fragment_about" to R.xml.pref_about,
        )

        /** トップ画面の項目のキーから、その画面を作る。 */
        fun createScreen(key: String): PreferenceFragmentCompat? = when (key) {
            "fragment_browser" -> BrowserSettingsFragment()
            "fragment_page" -> PageSettingFragment()
            "fragment_privacy" -> PrivacyFragment()
            "fragment_ui" -> UiSettingFragment()
            "fragment_action" -> ActionSettingsFragment()
            "fragment_speseddial" -> SpeeddialFragment()
            "fragment_application" -> ApplicationSettingsFragment()
            "fragment_import_export" -> ImportExportFragment()
            "fragment_about" -> AboutFragment()
            else -> null
        }
    }
}
