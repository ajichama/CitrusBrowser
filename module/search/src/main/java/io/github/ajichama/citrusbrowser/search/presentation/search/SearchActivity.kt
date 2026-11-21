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

package io.github.ajichama.citrusbrowser.search.presentation.search

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.databinding.DataBindingUtil
import androidx.databinding.Observable
import androidx.recyclerview.widget.LinearLayoutManager
import dagger.hilt.android.AndroidEntryPoint
import io.github.ajichama.citrusbrowser.core.utility.extensions.clipboardText
import io.github.ajichama.citrusbrowser.favicon.FaviconManager
import io.github.ajichama.citrusbrowser.search.R
import io.github.ajichama.citrusbrowser.search.databinding.SearchActivityBinding
import io.github.ajichama.citrusbrowser.search.databinding.SearchSeachBarBinding
import io.github.ajichama.citrusbrowser.search.presentation.qr.QrScanActivity
import io.github.ajichama.citrusbrowser.search.presentation.widget.SearchButton
import io.github.ajichama.citrusbrowser.ui.INTENT_EXTRA_MODE_FULLSCREEN
import io.github.ajichama.citrusbrowser.ui.app.ThemeActivity
import io.github.ajichama.citrusbrowser.ui.settings.AppPrefs
import io.github.ajichama.citrusbrowser.ui.theme.ThemeData
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min

@AndroidEntryPoint
class SearchActivity : ThemeActivity(), SearchButton.Callback, SearchSuggestAdapter.OnSearchSelectedListener, SuggestDeleteDialog.OnDeleteQuery {

    @Inject
    internal lateinit var faviconManager: FaviconManager

    private val viewModel by viewModels<SearchViewModel>()
    private lateinit var binding: SearchActivityBinding
    private lateinit var barBinding: SearchSeachBarBinding

    private var appData: Bundle? = null
    private var openNewTab: Int = 0

    // QR コードの読み取り（#18）。読み取った文字列は入力欄に入れるだけで、開くかどうかは利用者が決める。
    private val qrScanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringExtra(QrScanActivity.EXTRA_RESULT)
        if (result.resultCode != RESULT_OK || text.isNullOrEmpty()) return@registerForActivityResult
        barBinding.editText.run {
            setText(text)
            setSelection(text.length)
            requestFocus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.search_activity)
        barBinding = SearchSeachBarBinding.inflate(layoutInflater, binding.rootLayout, false)

        binding.lifecycleOwner = this
        barBinding.lifecycleOwner = this

        val intent = intent ?: throw IllegalStateException("Intent is null")

        val fullscreen = intent.getBooleanExtra(INTENT_EXTRA_MODE_FULLSCREEN, AppPrefs.fullscreen.get())
        if (fullscreen) {
            setStatusBarHidden(true)
        }

        val bottomBoxMode = intent.getBooleanExtra(EXTRA_REVERSE, false)
        if (bottomBoxMode) {
            binding.bottomBox.addView(barBinding.root)
        } else {
            binding.topBox.addView(barBinding.root)
        }
        barBinding.callback = this

        viewModel.also {
            binding.model = it
            barBinding.viewModel = it

            barBinding.searchUrlSpinner.adapter = SearchUrlSpinnerAdapter(
                this, it.suggestProviders.urls, faviconManager)
            it.providerSelection.set(it.suggestProviders.getSelectedIndex())
        }

        if (!AppPrefs.searchUrlShowIcon.get()) {
            barBinding.searchUrlSpinner.visibility = View.GONE
        }

        val searchButton = barBinding.searchButton
        val editText = barBinding.editText
        val recyclerView = binding.recyclerView

        ThemeData.getInstance()?.let { themeData ->
            if (themeData.toolbarBackgroundColor != 0)
                barBinding.root.setBackgroundColor(themeData.toolbarBackgroundColor)
            val textColor = themeData.toolbarTextColor
            if (textColor != 0) {
                editText.setTextColor(textColor)
                editText.setHintTextColor(textColor and 0xffffff or 0x55000000)
            }
            if (themeData.toolbarImageColor != 0) {
                searchButton.setColorFilter(themeData.toolbarImageColor)
                barBinding.qrScanButton.setColorFilter(themeData.toolbarImageColor)
            }
            if (themeData.statusBarColor != 0) {
                window.run {
                    WindowCompat.getInsetsController(this, decorView).isAppearanceLightStatusBars =
                        ThemeData.isUseLightStatusBar()
                    @Suppress("DEPRECATION")
                    statusBarColor = themeData.statusBarColor
                }
            }
        }

        searchButton.setSense(AppPrefs.swipebtn_sensitivity.get())

        editText.customSelectionActionModeCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu) = true

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = true

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                val text = viewModel.query

                var min = 0
                var max = text.length

                if (editText.isFocused) {
                    val selStart = editText.selectionStart
                    val selEnd = editText.selectionEnd


                    min = max(0, min(selStart, selEnd))
                    max = max(0, max(selStart, selEnd))
                }

                when (item.itemId) {
                    android.R.id.copy -> if (min == 0 && max == text.length && viewModel.decodedInitQuery == text) {
                        clipboardText = viewModel.initQuery!!
                        mode.finish()
                        return true
                    }
                    android.R.id.cut -> if (min == 0 && max == text.length && viewModel.decodedInitQuery == text) {
                        clipboardText = viewModel.initQuery!!
                        editText.setText("")
                        mode.finish()
                        return true
                    }
                }
                return false
            }

            override fun onDestroyActionMode(mode: ActionMode) = Unit
        }

        appData = intent.getBundleExtra(EXTRA_APP_DATA)
        openNewTab = intent.getIntExtra(EXTRA_OPEN_NEW_TAB, 0)

        recyclerView.layoutManager = LinearLayoutManager(this).apply {
            if (bottomBoxMode) reverseLayout = true
        }
        recyclerView.setOnOutSideClickListener { finish() }
        recyclerView.setOnClickListener { finish() }

        binding.adapter = SearchSuggestAdapter().also { it.listener = this }

        val initQuery = intent.getStringExtra(EXTRA_QUERY)
        if (initQuery != null) {
            viewModel.setInitQuery(initQuery)
            editText.setText(initQuery)
            viewModel.setQuery(initQuery)
        } else {
            editText.setText("")
            viewModel.setQuery("")
        }

        if (intent.getBooleanExtra(EXTRA_SELECT_INITIAL_QUERY, true)) {
            editText.selectAll()
        }

        barBinding.qrScanButton.setOnClickListener { startQrScan() }
        // 操作「QR コードを読み取る」から開いたときは、すぐに読み取りを始める（画面を作り直したときは除く）。
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_START_QR_SCAN, false)) {
            startQrScan()
        }
    }

    private fun startQrScan() {
        qrScanLauncher.launch(Intent(this, QrScanActivity::class.java))
    }

    override fun onStart() {
        super.onStart()
        if (AppPrefs.searchUrlShowIcon.get() && AppPrefs.searchUrlSaveSwitching.get())
            viewModel.providerSelection.addOnPropertyChangedCallback(callback)
    }

    override fun onPause() {
        super.onPause()
        viewModel.providerSelection.removeOnPropertyChangedCallback(callback)
    }

    override fun forceOpenUrl() {
        finish(SearchViewModel.SEARCH_MODE_URL)
    }

    override fun forceSearchWord() {
        finish(SearchViewModel.SEARCH_MODE_WORD)
    }

    override fun autoSearch() {
        finish(SearchViewModel.SEARCH_MODE_AUTO)
    }

    private fun finish(mode: Int) {
        val result = viewModel.getFinishResult(mode)
        if (result != null) {
            setResult(RESULT_OK, Intent().apply {
                putExtra(EXTRA_QUERY, result.query)
                putExtra(EXTRA_SEARCH_MODE, mode)
                putExtra(EXTRA_SEARCH_URL, result.url)
                putExtra(EXTRA_OPEN_NEW_TAB, openNewTab)
                appData?.let { putExtra(EXTRA_APP_DATA, it) }
            })
        } else {
            setResult(RESULT_CANCELED)
        }
        finish()
    }

    override fun recognizeSpeech() {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            startActivityForResult(intent, RESULT_REQUEST_SPEECH)
        } catch (e: ActivityNotFoundException) {
            e.printStackTrace()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            RESULT_REQUEST_SPEECH -> {
                if (resultCode != RESULT_OK || data == null) return
                val results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                if (!results.isNullOrEmpty()) {
                    val query = results[0]
                    barBinding.editText.run {
                        setText(query)
                        selectAll()
                    }
                }
            }
            else -> {
                super.onActivityResult(requestCode, resultCode, data)
            }
        }
    }

    override fun onSelectedQuery(query: String) {
        viewModel.setQuery(query)
        finish(SearchViewModel.SEARCH_MODE_AUTO)
    }

    override fun onInputQuery(query: String) {
        barBinding.editText.run {
            setText(query)
            setSelection(query.length)
        }
    }

    override fun onDeleteQuery(query: String) {
        SuggestDeleteDialog.newInstance(query).show(supportFragmentManager, "delete")
    }

    override fun onDelete(query: String) {
        viewModel.deleteQuery(query)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            finish()
        }
        return super.onTouchEvent(event)
    }

    private val callback = object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            AppPrefs.search_url.set(viewModel.suggestProviders[viewModel.providerSelection.get()].url)
            AppPrefs.commit(this@SearchActivity, AppPrefs.search_url)
            viewModel.saveProvider()
        }
    }

    companion object {
        const val EXTRA_QUERY = "io.github.ajichama.citrusbrowser.legacy.search.SearchActivity.extra.query"
        const val EXTRA_SELECT_INITIAL_QUERY = "io.github.ajichama.citrusbrowser.legacy.search.SearchActivity.extra.selectinitquery"
        const val EXTRA_APP_DATA = "io.github.ajichama.citrusbrowser.legacy.search.SearchActivity.extra.appdata"
        const val EXTRA_SEARCH_MODE = "io.github.ajichama.citrusbrowser.legacy.search.SearchActivity.extra.searchmode"
        const val EXTRA_SEARCH_URL = "io.github.ajichama.citrusbrowser.legacy.search.SearchActivity.extra.searchUrl"
        const val EXTRA_OPEN_NEW_TAB = "io.github.ajichama.citrusbrowser.legacy.search.SearchActivity.extra.openNewTab"
        const val EXTRA_REVERSE = "io.github.ajichama.citrusbrowser.legacy.search.SearchActivity.extra.reverse"
        /** true なら、開いてすぐ QR コードの読み取りを始める（#18）。 */
        const val EXTRA_START_QR_SCAN = "io.github.ajichama.citrusbrowser.legacy.search.SearchActivity.extra.startQrScan"

        const val SEARCH_MODE_AUTO = SearchViewModel.SEARCH_MODE_AUTO
        const val SEARCH_MODE_URL = SearchViewModel.SEARCH_MODE_URL
        const val SEARCH_MODE_WORD = SearchViewModel.SEARCH_MODE_WORD

        const val TAB_TYPE_CURRENT = 0
        const val TAB_TYPE_NEW_TAB = 1
        const val TAB_TYPE_NEW_RIGHT_TAB = 2

        private const val RESULT_REQUEST_SPEECH = 1
    }
}
