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

package io.github.ajichama.citrusbrowser.legacy.settings.search

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.getSystemService
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.ajichama.citrusbrowser.legacy.R

/**
 * 設定の検索の画面（#25）。入力欄に打つたびに結果を並べ、押すとその項目のある画面へ移る。
 * 移る処理は [Host]（設定の Activity）が受け持つ。
 */
class SettingsSearchFragment : Fragment() {

    interface Host {
        fun openSettingsSearchResult(entry: SettingsSearchEntry)
    }

    private var entries: List<SettingsSearchEntry> = emptyList()
    private var query: EditText? = null
    private var empty: TextView? = null
    private val adapter = ResultAdapter { entry ->
        hideKeyboard()
        (activity as? Host)?.openSettingsSearchResult(entry)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings_search, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        entries = SettingsIndexer(requireContext()).build()
        empty = view.findViewById(R.id.settingsSearchEmpty)
        view.findViewById<RecyclerView>(R.id.settingsSearchResults).also {
            it.layoutManager = LinearLayoutManager(requireContext())
            it.adapter = adapter
        }
        query = view.findViewById<EditText>(R.id.settingsSearchQuery).also { edit ->
            edit.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) hideKeyboard()
                actionId == EditorInfo.IME_ACTION_SEARCH
            }
        }
        // 初めて開いたときだけ、すぐ打てるようにキーボードを出す（結果から戻ったときは出さない）。
        if (savedInstanceState == null && adapter.itemCount == 0) {
            query?.let { edit ->
                edit.requestFocus()
                edit.post { requireContext().getSystemService<InputMethodManager>()?.showSoftInput(edit, 0) }
            }
        }
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        // 入力欄の文字は復元の後に戻るので、ここで結果を作り直し、以降は打つたびに更新する。
        val edit = query ?: return
        update(edit.text.toString())
        edit.doAfterTextChanged { update(it?.toString().orEmpty()) }
    }

    override fun onResume() {
        super.onResume()
        activity?.setTitle(R.string.search_settings)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        query = null
        empty = null
    }

    private fun update(text: String) {
        val results = SettingsSearch.find(entries, text)
        adapter.submit(results)
        empty?.visibility = if (text.isNotBlank() && results.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun hideKeyboard() {
        val edit = query ?: return
        requireContext().getSystemService<InputMethodManager>()?.hideSoftInputFromWindow(edit.windowToken, 0)
    }

    private class ResultAdapter(private val onClick: (SettingsSearchEntry) -> Unit) : RecyclerView.Adapter<ResultAdapter.Holder>() {

        private var items: List<SettingsSearchEntry> = emptyList()

        fun submit(list: List<SettingsSearchEntry>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_settings_search, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = items[position]
            holder.title.text = entry.title
            holder.summary.text = entry.summary
            holder.summary.visibility = if (entry.summary.isNullOrEmpty()) View.GONE else View.VISIBLE
            holder.path.text = entry.path.joinToString(" › ")
            holder.path.visibility = if (entry.path.isEmpty()) View.GONE else View.VISIBLE
            holder.itemView.setOnClickListener { onClick(entry) }
        }

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.settingsSearchTitle)
            val summary: TextView = view.findViewById(R.id.settingsSearchSummary)
            val path: TextView = view.findViewById(R.id.settingsSearchPath)
        }
    }
}
