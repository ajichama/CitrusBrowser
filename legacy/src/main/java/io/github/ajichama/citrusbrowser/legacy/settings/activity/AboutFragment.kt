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

package io.github.ajichama.citrusbrowser.legacy.settings.activity

import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import androidx.fragment.app.DialogFragment
import androidx.preference.Preference
import io.github.ajichama.citrusbrowser.core.utility.extensions.getVersionName
import io.github.ajichama.citrusbrowser.legacy.Constants
import io.github.ajichama.citrusbrowser.legacy.R
import io.github.ajichama.citrusbrowser.legacy.licenses.LicensesActivity
import io.github.ajichama.citrusbrowser.legacy.utils.AppUtils
import io.github.ajichama.citrusbrowser.legacy.utils.extensions.setClipboardWithToast
import io.github.ajichama.citrusbrowser.ui.extensions.intentFor

class AboutFragment : CitrusPreferenceFragment() {

    override fun onCreateCitrusPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val activity = activity ?: throw IllegalStateException()
        addPreferencesFromResource(R.xml.pref_about)
        findPreference<Preference>("version")!!.run {
            setOnPreferenceClickListener {
                activity.setClipboardWithToast(AppUtils.getVersionDeviceInfo(activity))
                true
            }
            summary = activity.getVersionName()
        }

        findPreference<Preference>("build")!!.summary = activity.getString(R.string.package_build)
        findPreference<Preference>("build_time")!!.summary = activity.getString(R.string.package_build_time)

        findPreference<Preference>("osl")!!.setOnPreferenceClickListener {
            startActivity(intentFor<LicensesActivity>())
            true
        }
        findPreference<Preference>("translation")!!.setOnPreferenceClickListener {
            TranslationDialog().show(childFragmentManager, "translation")
            true
        }

        // CitrusBrowser リポジトリのプライバシーポリシー (Privacy.md) を表示
        findPreference<Preference>("privacy_policy")!!.setOnPreferenceClickListener {
            startActivity(Intent().apply {
                setClassName(requireContext(), Constants.activity.MAIN_BROWSER)
                action = Constants.intent.ACTION_OPEN_DEFAULT
                data = Uri.parse("https://github.com/ajichama/CitrusBrowser/blob/main/Privacy.md")
            })
            true
        }
    }

    class TranslationDialog : DialogFragment() {
        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            val activity = requireActivity()
            return AlertDialog.Builder(activity)
                .setTitle(R.string.pref_translation)
                .setView(WebView(activity).apply { loadUrl("file:///android_asset/translators.html") })
                .create()
        }
    }
}
