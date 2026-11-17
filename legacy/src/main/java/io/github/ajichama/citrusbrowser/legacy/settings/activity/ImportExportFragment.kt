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

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.*
import android.widget.Toast
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import dagger.hilt.android.AndroidEntryPoint
import io.github.ajichama.citrusbrowser.bookmark.netscape.exportHtmlBookmark
import io.github.ajichama.citrusbrowser.bookmark.repository.BookmarkManager
import io.github.ajichama.citrusbrowser.core.utility.utils.FileUtils
import io.github.ajichama.citrusbrowser.legacy.R
import io.github.ajichama.citrusbrowser.legacy.backup.*
import io.github.ajichama.citrusbrowser.legacy.speeddial.io.backupSpeedDial
import io.github.ajichama.citrusbrowser.ui.extensions.registerForStartActivityForResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
import java.io.File
import java.io.IOException

@AndroidEntryPoint
class ImportExportFragment : CitrusPreferenceFragment() {
    private val portableModel: PortableImportViewModel by viewModels()
    private val portableLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) portableModel.prepare(uri)
    }

    override fun onCreateCitrusPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_import_export)
        findPreference<Preference>("portable_import")!!.setOnPreferenceClickListener { showPortableHelp(); true }

        findPreference<Preference>("import_sd_bookmark")!!.setOnPreferenceClickListener {
            showPortableHelp()
            false
        }

        findPreference<Preference>("export_sd_bookmark")!!.setOnPreferenceClickListener {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_TITLE, "bookmark_${FileUtils.getTimeFileName()}.dat")
            }
            exportBookmarkLauncher.launch(intent)
            false
        }

        findPreference<Preference>("import_html_bookmark")!!.setOnPreferenceClickListener {
            showPortableHelp()
            false
        }

        findPreference<Preference>("export_html_bookmark")!!.setOnPreferenceClickListener {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                type = "text/html"
                putExtra(Intent.EXTRA_TITLE, "bookmark_${FileUtils.getTimeFileName()}.html")
            }
            exportHtmlBookmarkLauncher.launch(intent)
            false
        }

        findPreference<Preference>("restore_speed_dial")!!.setOnPreferenceClickListener {
            showPortableHelp()
            true
        }

        findPreference<Preference>("backup_speed_dial")!!.setOnPreferenceClickListener {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_TITLE, "speedDial_${FileUtils.getTimeFileName()}$EXT_SPEED_DIAL")
            }
            backupSpeeddialLauncher.launch(intent)
            false
        }

        findPreference<Preference>("restore_settings")!!.setOnPreferenceClickListener {
            showPortableHelp()
            true
        }

        findPreference<Preference>("backup_settings")!!.setOnPreferenceClickListener {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_TITLE, "citrus_backup_${FileUtils.getTimeFileName()}$EXT")
            }
            backupSettingsLauncher.launch(intent)
            false
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                portableModel.state.collect { state ->
                    val busy = state is PortableImportState.Preparing || state is PortableImportState.Applying
                    preferenceScreen.isEnabled = !busy
                    findPreference<Preference>("portable_import")?.setSummary(when (state) {
                        PortableImportState.Preparing -> R.string.legacy_import_preparing
                        PortableImportState.Applying -> R.string.legacy_import_applying
                        else -> R.string.legacy_import_summary
                    })
                    if (childFragmentManager.isStateSaved) return@collect
                    when (state) {
                        is PortableImportState.Ready -> if (childFragmentManager.findFragmentByTag("portable-import-preview") == null) {
                            PortableImportDialog.preview(state.preview).show(childFragmentManager, "portable-import-preview")
                        }
                        is PortableImportState.Complete -> {
                            PortableImportDialog.complete(state.result).show(childFragmentManager, "portable-import-complete")
                            portableModel.acknowledge()
                        }
                        is PortableImportState.Failed -> {
                            val message = when { state.recovery -> R.string.legacy_import_recovery; state.applying -> R.string.legacy_import_apply_failed; else -> R.string.legacy_import_invalid }
                            AlertDialog.Builder(requireContext()).setTitle(R.string.failed).setMessage(message)
                                .setPositiveButton(android.R.string.ok, null).show()
                            portableModel.acknowledge()
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun showPortableHelp() {
        AlertDialog.Builder(requireContext()).setTitle(R.string.legacy_import_title)
            .setMessage(R.string.legacy_import_help)
            .setPositiveButton(R.string.legacy_import_select) { _, _ -> portableLauncher.launch(arrayOf("*/*")) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }
    private val exportBookmarkLauncher = registerForStartActivityForResult {
        if (it.resultCode == Activity.RESULT_OK) {
            val uri = it.data!!.data ?: return@registerForStartActivityForResult
            val context = requireContext().applicationContext
            val manager = BookmarkManager.getInstance(context)
            val internalFile = manager.file

            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                val result = withContext(Dispatchers.IO) { internalFile.copyTo(uri, context) }
                context.actionMessage(result)
            }
        } else {
            Toast.makeText(activity, R.string.failed, Toast.LENGTH_LONG).show()
        }
    }

    private val exportHtmlBookmarkLauncher = registerForStartActivityForResult {
        if (it.resultCode != Activity.RESULT_OK) return@registerForStartActivityForResult
        val uri = it.data!!.data ?: return@registerForStartActivityForResult
        val context = requireContext().applicationContext
        val manager = BookmarkManager.getInstance(context)

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            val result = manager.exportHtmlBookmark(context, uri)
            context.actionMessage(result)
        }
    }

    private val backupSpeeddialLauncher = registerForStartActivityForResult {
        if (it.resultCode != Activity.RESULT_OK) return@registerForStartActivityForResult
        val uri = it.data!!.data ?: return@registerForStartActivityForResult

        val context = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            val result = withContext(Dispatchers.IO) { context.backupSpeedDial(uri) }
            context.actionMessage(result)
        }
    }

    private val backupSettingsLauncher = registerForStartActivityForResult {
        if (it.resultCode != Activity.RESULT_OK) return@registerForStartActivityForResult
        val uri = it.data!!.data ?: return@registerForStartActivityForResult

        val context = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            val manager = BackupManager(context)
            val result = withContext(Dispatchers.IO) { manager.backup(context, uri) }
            context.actionMessage(result)
        }
    }

    private fun File.copyTo(uri: Uri, context: Context): Boolean {
        try {
            context.contentResolver.openOutputStream(uri)?.use { os ->
                inputStream().use { input ->
                    input.copyTo(os)
                    return true
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
        return false
    }

    private fun Context.actionMessage(state: Boolean) {
        val message = if (state) R.string.succeed else R.string.failed
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val EXT = ".citrusbackup"
        private const val EXT_SPEED_DIAL = ".citrusdial"
    }

}
