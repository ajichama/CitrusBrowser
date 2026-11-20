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

package io.github.ajichama.citrusbrowser.legacy.settings.activity

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import io.github.ajichama.citrusbrowser.legacy.R
import io.github.ajichama.citrusbrowser.legacy.backup.*
import io.github.ajichama.citrusbrowser.ui.RestartActivity

internal class PortableImportDialog : DialogFragment() {
    private val model: PortableImportViewModel by viewModels({ requireParentFragment() })
    private var selected: BooleanArray? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        if (args.getBoolean("complete")) {
            val message = getString(R.string.legacy_import_result, args.getInt("bookmarks"), args.getInt("added"), args.getInt("duplicates")) +
                "\n" + getString(R.string.legacy_import_result_config, args.getInt("configs"), args.getInt("scripts"), args.getInt("gestures"), args.getInt("preserved"), args.getInt("ignored")) +
                (if (args.getInt("readLater") > 0) "\n" + getString(R.string.legacy_import_result_read_later, args.getInt("readLater")) else "") +
                (if (args.getBoolean("restart")) "\n\n" + getString(R.string.legacy_import_restart) else "") +
                (if (args.getBoolean("warning")) "\n\n" + getString(R.string.legacy_import_normalization_warning) else "")
            return AlertDialog.Builder(requireContext()).setTitle(R.string.succeed).setMessage(message)
                .setPositiveButton(if (args.getBoolean("restart")) R.string.legacy_import_restart_now else android.R.string.ok) { _, _ ->
                    if (args.getBoolean("restart")) startActivity(RestartActivity.createIntent(requireContext()))
                }.apply { if (args.getBoolean("restart")) setNegativeButton(R.string.legacy_import_restart_later, null) }.create()
        }
        val categories = args.getStringArrayList("categories")!!.map(ImportCategory::valueOf)
        val labels = categories.map { category -> when (category) {
            ImportCategory.Settings -> getString(R.string.legacy_import_settings, args.getInt("prefs"), args.getInt("actions"), args.getInt("configs"))
            ImportCategory.Bookmarks -> getString(R.string.legacy_import_bookmarks, args.getInt("bookmarks"))
            ImportCategory.SpeedDial -> getString(R.string.legacy_import_dial, args.getInt("dial"))
            ImportCategory.Scripts -> getString(R.string.legacy_import_scripts, args.getInt("scripts"))
            ImportCategory.Gestures -> getString(R.string.legacy_import_gestures, args.getInt("gestures"))
            ImportCategory.ReadItLater -> getString(R.string.legacy_import_read_later, args.getInt("readLater"))
        } }.toTypedArray()
        val choices = savedInstanceState?.getBooleanArray("selected") ?: BooleanArray(categories.size)
        selected = choices
        // 読み込まないファイル（キャッシュ等）と、取り込むデータの中で除外する項目を分けて、一覧の下に説明する。
        val notes = listOfNotNull(
            args.getInt("skippedFiles").takeIf { it > 0 }?.let { getString(R.string.legacy_import_skipped_files, it) },
            args.getInt("ignored").takeIf { it > 0 }?.let { getString(R.string.legacy_import_excluded_items, it) },
        )
        val padding = (resources.displayMetrics.density * 24).toInt()
        val noteView = notes.takeIf { it.isNotEmpty() }?.let { lines ->
            TextView(requireContext()).apply {
                text = lines.joinToString("\n\n")
                setPadding(padding, padding / 2, padding, 0)
            }
        }
        return AlertDialog.Builder(requireContext())
            .setTitle(R.string.legacy_import_review)
            .setMultiChoiceItems(labels, choices) { _, which, checked -> choices[which] = checked }
            .apply { if (noteView != null) setView(noteView) }
            .setPositiveButton(R.string.legacy_import_confirm) { _, _ ->
                val chosen = categories.filterIndexed { index, _ -> choices[index] }.toSet()
                if (chosen.isEmpty()) { model.cancelPreview(); Toast.makeText(requireContext(), R.string.legacy_import_choose, Toast.LENGTH_LONG).show() }
                else model.confirm(chosen)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> model.cancelPreview() }.create()
    }

    override fun onSaveInstanceState(outState: Bundle) { selected?.let { outState.putBooleanArray("selected", it) }; super.onSaveInstanceState(outState) }
    override fun onCancel(dialog: DialogInterface) { if (!requireArguments().getBoolean("complete")) model.cancelPreview(); super.onCancel(dialog) }

    companion object {
        internal fun preview(value: ImportPreview) = PortableImportDialog().apply {
            arguments = Bundle().apply {
                putStringArrayList("categories", ArrayList(value.categories.map { it.name }))
                putInt("prefs", value.preferences); putInt("actions", value.actions); putInt("bookmarks", value.bookmarks)
                putInt("dial", value.speedDial); putInt("ignored", value.ignored)
                putInt("readLater", value.readItLater); putInt("skippedFiles", value.skippedFiles)
                putInt("configs", value.configurations); putInt("scripts", value.scripts); putInt("gestures", value.gestures)
            }
        }
        internal fun complete(value: ImportResult) = PortableImportDialog().apply {
            arguments = Bundle().apply {
                putBoolean("complete", true); putInt("bookmarks", value.bookmarks)
                putInt("added", value.speedDialAdded); putInt("duplicates", value.speedDialDuplicates); putBoolean("restart", value.restart)
                putBoolean("warning", value.normalizationWarning)
                putInt("configs", value.settingsAdded); putInt("scripts", value.scriptsAdded); putInt("gestures", value.gesturesAdded)
                putInt("preserved", value.preserved); putInt("ignored", value.ignored)
                putInt("readLater", value.readItLaterAdded)
            }
        }
    }
}
