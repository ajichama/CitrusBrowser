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

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreference
import io.github.ajichama.citrusbrowser.adblock.ui.original.AdBlockActivity
import io.github.ajichama.citrusbrowser.core.utility.storage.DEFAULT_DOWNLOAD_PATH
import io.github.ajichama.citrusbrowser.legacy.R
import io.github.ajichama.citrusbrowser.ui.extensions.registerForStartActivityForResult
import io.github.ajichama.citrusbrowser.ui.preference.StrToIntListPreference
import io.github.ajichama.citrusbrowser.ui.settings.AppPrefs
import io.github.ajichama.citrusbrowser.ui.utils.checkLocalNetworkPermission
import io.github.ajichama.citrusbrowser.ui.utils.isLocalNetworkPermissionRequired
import io.github.ajichama.citrusbrowser.ui.utils.openRequestPermissionSettings

class BrowserSettingsFragment : CitrusPreferenceFragment() {

    private var replaceFragment: ReplaceFragmentListener? = null
    private val localNetworkLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refreshLocalNetworkPermission()
        if (!granted) showLocalNetworkPermission(denied = true)
    }

    override fun onCreateCitrusPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_browser_settings)
        refreshLocalNetworkPermission()
        findPreference<Preference>("local_network_permission")!!.setOnPreferenceClickListener {
            showLocalNetworkPermission()
            true
        }

        val pref = findPreference<StrToIntListPreference>("search_suggest")!!
        val suggest = findPreference<Preference>("search_suggest_engine")!!

        suggest.isEnabled = pref.value != 2

        pref.setOnPreferenceChangeListener { _, newValue ->
            suggest.isEnabled = newValue as Int != 2
            true
        }

        val savePinned = findPreference<Preference>("save_pinned_tabs")!!
        val saveLast = findPreference<SwitchPreference>("save_last_tabs")!!

        savePinned.isEnabled = !saveLast.isChecked
        saveLast.setOnPreferenceChangeListener { _, newValue ->
            savePinned.isEnabled = !(newValue as Boolean)
            true
        }

        findPreference<Preference>("ad_block_settings")!!.setOnPreferenceClickListener {
            startActivity(Intent(activity, AdBlockActivity::class.java))
            true
        }

        findPreference<Preference>("download_folder")!!.setOnPreferenceClickListener {
            DownloadFolderDialog(AppPrefs.download_folder.get()).show(childFragmentManager, "")
            true
        }
    }

    override fun onResume() {
        super.onResume()
        // OS のアプリ設定から戻った場合も、永続化した独自の許可フラグには頼らない。
        refreshLocalNetworkPermission()
    }

    private fun refreshLocalNetworkPermission() {
        val context = context ?: return
        findPreference<Preference>("local_network_permission")?.apply {
            isVisible = isLocalNetworkPermissionRequired()
            summary = getString(if (context.checkLocalNetworkPermission()) R.string.local_network_granted else R.string.local_network_not_granted)
        }
    }

    private fun showLocalNetworkPermission(denied: Boolean = false) {
        if (!isLocalNetworkPermissionRequired() || !isAdded || childFragmentManager.isStateSaved ||
            childFragmentManager.findFragmentByTag(LOCAL_NETWORK_DIALOG) != null) return
        LocalNetworkPermissionDialog().apply { arguments = Bundle().apply { putBoolean("denied", denied) } }
            .show(childFragmentManager, LOCAL_NETWORK_DIALOG)
    }

    private fun requestLocalNetworkPermission() {
        if (isLocalNetworkPermissionRequired()) localNetworkLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
    }

    class LocalNetworkPermissionDialog : DialogFragment() {
        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            val granted = requireContext().checkLocalNetworkPermission()
            return AlertDialog.Builder(requireContext())
                .setTitle(R.string.local_network_title)
                .setMessage(if (granted) R.string.local_network_granted_explanation
                    else if (arguments?.getBoolean("denied") == true) R.string.local_network_denied_explanation
                    else R.string.local_network_explanation)
                .setNegativeButton(android.R.string.cancel, null)
                .apply {
                    if (!granted) setPositiveButton(R.string.local_network_request) { _, _ ->
                        (parentFragment as? BrowserSettingsFragment)?.requestLocalNetworkPermission()
                    }
                    setNeutralButton(R.string.local_network_app_settings) { _, _ ->
                        requireContext().openRequestPermissionSettings(getString(R.string.local_network_settings_hint))
                    }
                }.create()
        }
    }
    override fun onPreferenceStartScreen(pref: PreferenceScreen): Boolean {
        if (pref.key == "ps_search") {
            replaceFragment?.replaceFragment(SuggestScreen(), "ps_search")
            return true
        }
        return false
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            REQUEST_FOLDER -> {
                val activity = activity ?: return

            }
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)

        replaceFragment = activity as ReplaceFragmentListener
    }

    override fun onDetach() {
        super.onDetach()

        replaceFragment = null
    }

    private fun selectDownloadDirectory(isDefault: Boolean) {
        if (isDefault) {
            saveDownloadFolder(DEFAULT_DOWNLOAD_PATH)
        } else {
            try {
                downloadDirectoryLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))
            } catch (e: Exception) {
                Toast.makeText(requireContext(), R.string.failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val downloadDirectoryLauncher = registerForStartActivityForResult {
        if (it.resultCode != Activity.RESULT_OK) return@registerForStartActivityForResult

        val uri = it.data?.data ?: return@registerForStartActivityForResult

        val context = requireContext()
        if (uri.scheme == "content") {
            context.contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }

        saveDownloadFolder(uri.toString())
    }

    private fun saveDownloadFolder(directory: String) {
        AppPrefs.download_folder.set(directory)
        AppPrefs.commit(context, AppPrefs.download_folder)
    }

    companion object {
        private const val REQUEST_FOLDER = 1
        private const val LOCAL_NETWORK_DIALOG = "local-network-permission"
    }

    class SuggestScreen : CitrusPreferenceFragment() {

        private lateinit var suggestEngine: Preference
        private lateinit var suggestHistory: Preference
        private lateinit var suggestBookmark: Preference

        override fun onCreateCitrusPreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.pref_browser_settings, "ps_search")

            val suggestMode = findPreference<StrToIntListPreference>("search_suggest")!!
            suggestEngine = findPreference("search_suggest_engine")!!
            suggestHistory = findPreference("search_suggest_histories")!!
            suggestBookmark = findPreference("search_suggest_bookmarks")!!

            setSuggestEnable(suggestMode.value)
            suggestMode.setOnPreferenceChangeListener { _, newValue ->
                setSuggestEnable(newValue as Int)
                true
            }
        }

        private fun setSuggestEnable(type: Int) {
            val enable = type != 3
            suggestEngine.isEnabled = enable
            suggestHistory.isEnabled = enable
            suggestBookmark.isEnabled = enable
        }
    }

    class DownloadFolderDialog : DialogFragment() {

        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            val uri = requireArguments().getString(ARG_URI)
            val selection = if (uri.isNullOrEmpty() || uri == DEFAULT_DOWNLOAD_PATH) 0 else 1
            val items = arrayOf(getString(R.string.default_text), getString(R.string.custom))

            return AlertDialog.Builder(requireActivity())
                .setTitle(R.string.pref_download_folder)
                .setSingleChoiceItems(items, selection) { dialog, which ->
                    (parentFragment as BrowserSettingsFragment).selectDownloadDirectory(which == 0)
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        }

        companion object {
            private const val ARG_URI = "uri"

            operator fun invoke(uri: String) = DownloadFolderDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_URI, uri)
                }
            }
        }
    }
}
