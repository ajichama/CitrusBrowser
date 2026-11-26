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

package io.github.ajichama.citrusbrowser.bookmark.view

import android.app.Activity.RESULT_OK
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import io.github.ajichama.citrusbrowser.bookmark.R
import io.github.ajichama.citrusbrowser.bookmark.databinding.FragmentBookmarkBinding
import io.github.ajichama.citrusbrowser.bookmark.item.BookmarkFolder
import io.github.ajichama.citrusbrowser.bookmark.item.BookmarkItem
import io.github.ajichama.citrusbrowser.bookmark.item.BookmarkSite
import io.github.ajichama.citrusbrowser.bookmark.overflow.MenuType
import io.github.ajichama.citrusbrowser.bookmark.repository.BookmarkFinder
import io.github.ajichama.citrusbrowser.bookmark.repository.BookmarkManager
import io.github.ajichama.citrusbrowser.bookmark.repository.HideMenuRepository
import io.github.ajichama.citrusbrowser.browser.connecter.openable.OpenUrl
import io.github.ajichama.citrusbrowser.browser.connecter.openable.OpenUrlList
import io.github.ajichama.citrusbrowser.favicon.FaviconManager
import io.github.ajichama.citrusbrowser.ui.*
import io.github.ajichama.citrusbrowser.ui.app.LongPressFixActivity
import io.github.ajichama.citrusbrowser.ui.extensions.addCallback
import io.github.ajichama.citrusbrowser.ui.extensions.applyIconColor
import io.github.ajichama.citrusbrowser.ui.extensions.setClipboardWithToast
import io.github.ajichama.citrusbrowser.ui.settings.AppPrefs
import io.github.ajichama.citrusbrowser.ui.utils.PackageUtils.createShortcut
import io.github.ajichama.citrusbrowser.ui.utils.shareWeb
import io.github.ajichama.citrusbrowser.ui.widget.breadcrumbs.BreadcrumbsView
import io.github.ajichama.citrusbrowser.ui.widget.breadcrumbs.BreadcrumbsViewAdapter
import io.github.ajichama.citrusbrowser.ui.widget.recycler.RecyclerTouchLocationDetector
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class BookmarkFragment : Fragment(), BookmarkItemAdapter.OnBookmarkRecyclerListener, ActionMode.Callback, BreadcrumbsView.OnBreadcrumbsViewClickListener {

    private var pickMode: Boolean = false

    private lateinit var adapter: BookmarkItemAdapter
    private lateinit var mManager: BookmarkManager
    private lateinit var mCurrentFolder: BookmarkFolder

    private lateinit var locationDetector: RecyclerTouchLocationDetector

    private var actionMode: ActionMode? = null

    private val root: BookmarkFolder
        get() = mManager.root

    private var showBreadCrumb = true

    private lateinit var breadcrumbAdapter: BreadcrumbsViewAdapter<BookmarkCrumb>

    /** 画面の中の検索の結果（#34）。検索していないときは null。 */
    private var searchHits: List<BookmarkFinder.Hit>? = null
    private var searchQuery = ""
    private var searchMenuItem: MenuItem? = null
    private var optionsMenu: Menu? = null

    @Inject
    internal lateinit var hideMenuRepository: HideMenuRepository

    @Inject
    internal lateinit var faviconManager: FaviconManager

    private var viewBinding: FragmentBookmarkBinding? = null

    private val binding: FragmentBookmarkBinding
        get() = viewBinding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        setHasOptionsMenu(true)
        viewBinding = FragmentBookmarkBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val activity = requireActivity()
        val arguments = arguments ?: throw IllegalArgumentException()

        (activity as AppCompatActivity).run {
            setSupportActionBar(binding.toolBar)
            supportActionBar?.run {
                setDisplayHomeAsUpEnabled(true)
            }
        }

        val recyclerView = binding.recyclerView
        val breadCrumbsView = binding.breadCrumbsView

        recyclerView.layoutManager = LinearLayoutManager(activity)
        val helper = ItemTouchHelper(Touch())
        helper.attachToRecyclerView(recyclerView)
        recyclerView.addItemDecoration(helper)

        pickMode = arguments.getBoolean(MODE_PICK)

        mManager = BookmarkManager.getInstance(activity)

        locationDetector = RecyclerTouchLocationDetector()

        recyclerView.addOnItemTouchListener(locationDetector)

        breadcrumbAdapter = BreadcrumbsViewAdapter(activity, breadCrumbsView)
        breadCrumbsView.adapter = breadcrumbAdapter

        val firstPos = getFirstPosition(arguments)

        binding.toolbarAppbar.elevation = 0f
        addAllFolderCrumbs(firstPos)
        breadCrumbsView.listener = this

        binding.fastScroller.let {
            it.attachRecyclerView(recyclerView)
            it.attachAppBarLayout(binding.coordinator, binding.subBar)
            it.isFixed = AppPrefs.touch_scrollbar_fixed_toolbar.get()
        }

        setList(firstPos)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(this) {
            if (searchHits != null) {
                closeSearch()
                return@addCallback
            }
            if (adapter.isSortMode) {
                adapter.isSortMode = false
                Toast.makeText(activity, R.string.end_sort, Toast.LENGTH_SHORT).show()
                return@addCallback
            } else if (adapter.isMultiSelectMode) {
                adapter.isMultiSelectMode = false
                return@addCallback
            }

            val parent = mCurrentFolder.parent
            if (parent != null) {
                setList(parent)
                breadcrumbAdapter.select(breadcrumbAdapter.selectedIndex - 1)
                binding.subBar.setExpanded(true, false)
            } else {
                requireActivity().finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.apply {
            if (showBreadCrumb) {
                toolbarAppbar.elevation = 0f
            } else {
                toolbarAppbar.elevation = subBar.elevation
            }
        }
    }

    override fun onBreadcrumbItemClick(position: Int) {
        val folder = breadcrumbAdapter.crumbs[position].folder
        breadcrumbAdapter.select(position)
        if (searchHits != null) {
            // 検索を閉じると、mCurrentFolder の中身を表示し直す。
            mCurrentFolder = folder
            closeSearch()
        } else {
            setList(folder)
        }
    }

    private fun setList(folder: BookmarkFolder) {
        mCurrentFolder = folder
        setTitle(folder)
        showItems(folder.itemList)
    }

    private fun showItems(items: MutableList<BookmarkItem>) {
        val activity = requireActivity()
        adapter = if (AppPrefs.bookmarkSimpleDisplay.get()) {
            BookmarkSingleLineAdapter(activity, items, pickMode, AppPrefs.openBookmarkNewTab.get(), AppPrefs.fontSizeBookmark.get(), faviconManager, this)
        } else {
            BookmarkFullAdapter(activity, items, pickMode, AppPrefs.openBookmarkNewTab.get(), AppPrefs.fontSizeBookmark.get(), faviconManager, this)
        }

        binding.recyclerView.adapter = adapter
    }

    private fun startSearch() {
        // 並べ替え・複数選択は今のフォルダの並びを前提にするので、検索の間は使わない。
        adapter.isSortMode = false
        actionMode?.finish()
        searchQuery = ""
        searchHits = emptyList()
        showItems(mutableListOf())
        setOtherMenuItemsVisible(false)
    }

    /** フォルダの追加・並べ替え・表示の切り替えは今のフォルダに対する操作なので、検索の間は隠す（戻すのは endSearch）。 */
    private fun setOtherMenuItemsVisible(visible: Boolean) {
        val menu = optionsMenu ?: return
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            if (item.itemId != R.id.searchBookmark) item.isVisible = visible
        }
    }

    private fun updateSearch(query: String) {
        searchQuery = query
        val hits = BookmarkFinder.find(root, query)
        searchHits = hits
        showItems(hits.mapTo(mutableListOf()) { it.item })
    }

    /** 編集・削除の後に、同じ言葉で探し直す。 */
    private fun refreshSearch() {
        if (searchHits != null && viewBinding != null) updateSearch(searchQuery)
    }

    private fun endSearch(rebuildMenu: Boolean = true) {
        searchHits = null
        // 閉じる処理の途中で項目を表示に戻しても上のバーに反映されないので、メニューを作り直して戻す。
        if (rebuildMenu) activity?.invalidateOptionsMenu()
        if (viewBinding != null) setList(mCurrentFolder)
    }

    private fun closeSearch() {
        val item = searchMenuItem
        if (item != null && item.isActionViewExpanded) {
            item.collapseActionView()
        } else {
            endSearch()
        }
    }

    /** 検索の結果から、フォルダ（または入っているフォルダ）を開く。 */
    private fun showFolderFromSearch(folder: BookmarkFolder) {
        breadcrumbAdapter.select(0)
        BookmarkFinder.pathOf(folder).drop(1).forEach { breadcrumbAdapter.addItem(getCrumb(it)) }
        mCurrentFolder = folder
        closeSearch()
        binding.subBar.setExpanded(true, false)
    }

    override fun onRecyclerItemClicked(v: View, position: Int) {
        val hits = searchHits
        if (hits != null) {
            when (val item = hits.getOrNull(position)?.item) {
                is BookmarkSite -> {
                    sendUrl(item.url, AppPrefs.newtabBookmark.get())
                    activity?.finish()
                }
                is BookmarkFolder -> showFolderFromSearch(item)
                else -> Unit
            }
            return
        }
        when (val item = mCurrentFolder[position]) {
            is BookmarkSite -> {
                if (pickMode) {
                    pickBookmark(item)
                } else {
                    sendUrl(item.url, AppPrefs.newtabBookmark.get())
                }
                activity?.finish()
            }
            is BookmarkFolder -> {
                setList(item)
                breadcrumbAdapter.addItem(getCrumb(item))
                binding.subBar.setExpanded(true, false)
            }
            else -> throw IllegalStateException("Unknown BookmarkItem type")
        }
    }

    override fun onRecyclerItemLongClicked(v: View, position: Int): Boolean {
        return if (!adapter.isSortMode && !pickMode && searchHits == null) {
            if (adapter.isMultiSelectMode) {
                adapter.toggle(position)
            } else {
                (activity as AppCompatActivity).startSupportActionMode(this)
                adapter.isMultiSelectMode = true
                adapter.setSelect(position, true)
            }
            true
        } else {
            false
        }
    }

    override fun onIconClick(v: View, position: Int) {
        val item = adapter[position]
        if (item is BookmarkSite) {
            sendUrl(item.url, AppPrefs.openBookmarkIconAction.get())
        }
    }

    override fun onShowMenu(v: View, position: Int) {
        if (searchHits != null) {
            showSearchContextMenu(v, position)
        } else {
            showContextMenu(v, position)
        }
    }

    override fun onCancelMultiSelectMode() {
        actionMode?.finish()
    }

    private fun sendUrl(url: String?, target: Int) {
        val activity = requireActivity()

        if (url != null) {
            activity.setResult(RESULT_OK, Intent().apply {
                putExtra(INTENT_EXTRA_OPENABLE, OpenUrl(url, target))
            })
        }
        activity.finish()
    }

    private fun sendUrl(list: Collection<BookmarkItem>?, target: Int) {
        val activity = requireActivity()

        if (list != null) {
            val urlList = list.filterIsInstance<BookmarkSite>().map { it.url }

            if (urlList.isEmpty())
                return

            activity.setResult(RESULT_OK, Intent().apply {
                putExtra(INTENT_EXTRA_OPENABLE, OpenUrlList(urlList, target))
            })
        }
        activity.finish()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        if (!pickMode) {
            inflater.inflate(R.menu.bookmark, menu)
            val show = AppPrefs.bookmarkBreadcrumbs.get()
            menu.findItem(R.id.breadcrumbs).isChecked = show
            menu.findItem(R.id.simpleDisplay).isChecked = AppPrefs.bookmarkSimpleDisplay.get()
            showBreadCrumbs(show)
            setUpSearch(menu, menu.findItem(R.id.searchBookmark))

            applyIconColor(menu)
        }
    }

    private fun setUpSearch(menu: Menu, item: MenuItem) {
        // メニューを作り直したときは、開いたままの検索を閉じた状態にそろえる。
        if (searchHits != null) endSearch(rebuildMenu = false)
        optionsMenu = menu
        searchMenuItem = item
        (item.actionView as SearchView).apply {
            queryHint = getString(R.string.search_bookmarks_hint)
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String): Boolean {
                    clearFocus()
                    return true
                }

                override fun onQueryTextChange(newText: String): Boolean {
                    if (searchHits != null) updateSearch(newText)
                    return true
                }
            })
        }
        item.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                startSearch()
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                endSearch()
                return true
            }
        })
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val activity = requireActivity()

        when (item.itemId) {
            R.id.addFolder -> {
                AddBookmarkFolderDialog(activity, mManager, getString(R.string.new_folder_name), mCurrentFolder)
                    .setOnClickListener { _, _ -> adapter.notifyDataSetChanged() }
                    .show()
                return true
            }
            R.id.sort -> {
                val next = !adapter.isSortMode
                adapter.isSortMode = next

                Toast.makeText(activity, if (next) R.string.start_sort else R.string.end_sort, Toast.LENGTH_SHORT).show()
                return true
            }
            R.id.sortByName -> {
                // 保存されている順番を書き換え、手で並べた順には戻せないので、実行する前に確かめる（#33）。
                val folder = mCurrentFolder
                AlertDialog.Builder(activity)
                    .setTitle(R.string.sort_by_name)
                    .setMessage(R.string.sort_by_name_confirm)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        mManager.sortByName(folder, activity.resources.configuration.locales[0])
                        mManager.save()
                        adapter.notifyDataSetChanged()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                return true
            }
            R.id.breadcrumbs -> {
                val checked = !item.isChecked
                item.isChecked = checked
                AppPrefs.bookmarkBreadcrumbs.set(checked)
                AppPrefs.commit(activity, AppPrefs.bookmarkBreadcrumbs)
                showBreadCrumbs(checked)
                return true
            }
            R.id.simpleDisplay -> {
                val checked = !item.isChecked
                item.isChecked = checked
                AppPrefs.bookmarkSimpleDisplay.set(checked)
                AppPrefs.commit(activity, AppPrefs.bookmarkSimpleDisplay)
                setList(mCurrentFolder)
                return true
            }
        }
        return false
    }

    private fun getSelectedBookmarks(items: List<Int>): List<BookmarkItem> = items.map { mCurrentFolder[it] }

    private fun showContextMenu(v: View, index: Int) {
        val activity = requireActivity()

        val menu = PopupMenu(activity, v, locationDetector.gravity)
        val inflater = menu.menuInflater
        val bookmarkItem: BookmarkItem?
        if (pickMode) {
            bookmarkItem = adapter[index]
            menu.menu.add(R.string.select_this_item).setOnMenuItemClickListener {

                if (bookmarkItem is BookmarkSite) {
                    pickBookmark(bookmarkItem)
                } else {
                    val sender = Intent(requireContext(), BookmarkActivity::class.java)
                    sender.putExtra("id", bookmarkItem.id)

                    val intent = Intent()
                    intent.putExtra(Intent.EXTRA_TITLE, bookmarkItem.title)
                    intent.putExtra(Intent.EXTRA_TEXT, sender.toUri(Intent.URI_INTENT_SCHEME))
                    activity.setResult(RESULT_OK, intent)
                }
                activity.finish()
                false
            }
        } else {
            bookmarkItem = when {
                adapter.isMultiSelectMode -> {
                    inflater.inflate(R.menu.bookmark_multiselect_menu, menu.menu)
                    null
                }
                mCurrentFolder[index] is BookmarkSite -> {
                    inflater.inflate(R.menu.bookmark_site_menu, menu.menu)
                    val menuList = menu.menu
                    hideMenuRepository.getHideMenu(MenuType.SITE).forEach(menuList::removeItem)
                    adapter[index]
                }
                else -> {
                    inflater.inflate(R.menu.bookmark_folder_menu, menu.menu)
                    val menuList = menu.menu
                    hideMenuRepository.getHideMenu(MenuType.FOLDER).forEach(menuList::removeItem)
                    adapter[index]
                }
            }
        }

        menu.setOnMenuItemClickListener { item ->
            onContextMenuClick(item.itemId, bookmarkItem, index)
            true
        }
        menu.show()
    }

    /** 検索の結果の行のメニュー（#34）。並び順に関わる操作は出さず、「入っているフォルダを開く」を足す。 */
    private fun showSearchContextMenu(v: View, position: Int) {
        val hit = searchHits?.getOrNull(position) ?: return
        val isSite = hit.item is BookmarkSite

        val popup = PopupMenu(requireActivity(), v, locationDetector.gravity)
        val menu = popup.menu
        popup.menuInflater.inflate(if (isSite) R.menu.bookmark_site_menu else R.menu.bookmark_folder_menu, menu)
        hideMenuRepository.getHideMenu(if (isSite) MenuType.SITE else MenuType.FOLDER).forEach(menu::removeItem)
        menu.removeItem(R.id.moveBookmark)
        menu.removeItem(R.id.moveUp)
        menu.removeItem(R.id.moveDown)
        menu.add(Menu.NONE, R.id.openContainingFolder, Menu.NONE, R.string.open_containing_folder)

        popup.setOnMenuItemClickListener { item ->
            onSearchContextMenuClick(item.itemId, hit)
            true
        }
        popup.show()
    }

    private fun onSearchContextMenuClick(id: Int, hit: BookmarkFinder.Hit) {
        val activity = requireActivity()
        val item = hit.item

        when (id) {
            R.id.openContainingFolder -> showFolderFromSearch(hit.parent)
            R.id.editBookmark -> when (item) {
                is BookmarkSite -> AddBookmarkSiteDialog(activity, mManager, item)
                    .setOnClickListener { _, _ -> refreshSearch() }
                    .show()
                is BookmarkFolder -> AddBookmarkFolderDialog(activity, mManager, item)
                    .setOnClickListener { _, _ -> refreshSearch() }
                    .show()
            }
            R.id.deleteBookmark -> AlertDialog.Builder(activity)
                .setTitle(R.string.confirm)
                .setMessage(R.string.confirm_delete_bookmark)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    mManager.remove(hit.parent, item)
                    mManager.save()
                    refreshSearch()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            // 開く・共有・URL のコピー・ホームへの追加・すべて開く は、位置を使わないので共通の処理に任せる。
            else -> onContextMenuClick(id, item, -1)
        }
    }

    private fun pickBookmark(site: BookmarkSite) {
        val activity = requireActivity()

        val intent = Intent()
        intent.putExtra(Intent.EXTRA_TITLE, site.title)
        intent.putExtra(Intent.EXTRA_TEXT, site.url)

        val icon = adapter.getFavicon(site)
        if (icon != null) {
            intent.putExtra(Intent.EXTRA_STREAM, icon)
        }

        activity.setResult(RESULT_OK, intent)
    }

    private fun onContextMenuClick(id: Int, item: BookmarkItem?, index: Int) {
        val activity = requireActivity()

        when (id) {
            R.id.open -> sendUrl((item as BookmarkSite).url, BROWSER_LOAD_URL_TAB_CURRENT)
            R.id.openNew -> sendUrl((item as BookmarkSite).url, BROWSER_LOAD_URL_TAB_NEW)
            R.id.openBg -> sendUrl((item as BookmarkSite).url, BROWSER_LOAD_URL_TAB_BG)
            R.id.openNewRight -> sendUrl((item as BookmarkSite).url, BROWSER_LOAD_URL_TAB_NEW_RIGHT)
            R.id.openBgRight -> sendUrl((item as BookmarkSite).url, BROWSER_LOAD_URL_TAB_BG_RIGHT)
            R.id.share -> {
                val site = item as BookmarkSite
                shareWeb(activity, site.url, site.title)
            }
            R.id.copyUrl -> activity.setClipboardWithToast((item as BookmarkSite).url)
            R.id.addToHome -> {
                val url = (item as BookmarkSite).url
                val bitmap = faviconManager[url]
                createShortcut(activity, item.title, url, bitmap)
            }
            R.id.editBookmark -> if (item is BookmarkSite) {
                AddBookmarkSiteDialog(activity, mManager, item)
                    .setOnClickListener { _, _ -> adapter.notifyDataSetChanged() }
                    .show()
            } else if (item is BookmarkFolder) {
                AddBookmarkFolderDialog(activity, mManager, item)
                    .setOnClickListener { _, _ -> adapter.notifyDataSetChanged() }
                    .show()
            }
            R.id.moveBookmark -> BookmarkFoldersDialog(activity, mManager)
                    .setTitle(R.string.move_bookmark)
                    .setCurrentFolder(mCurrentFolder, item)
                .setOnFolderSelectedListener { folder ->
                        mManager.moveTo(mCurrentFolder, folder, index)

                        mManager.save()
                        adapter.notifyDataSetChanged()
                        false
                    }
                    .show()
            R.id.moveUp -> if (index > 0) {
                Collections.swap(mCurrentFolder.itemList, index - 1, index)
                mManager.save()
                adapter.notifyDataSetChanged()
            }
            R.id.moveDown -> if (index < mCurrentFolder.size() - 1) {
                Collections.swap(mCurrentFolder.itemList, index + 1, index)
                mManager.save()
                adapter.notifyDataSetChanged()
            }
            R.id.deleteBookmark -> AlertDialog.Builder(activity)
                    .setTitle(R.string.confirm)
                    .setMessage(R.string.confirm_delete_bookmark)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        mManager.remove(mCurrentFolder, index)
                        mManager.save()
                        adapter.notifyDataSetChanged()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            R.id.openAllNew -> {
                val items = if (item is BookmarkFolder) {
                    item.itemList
                } else {
                    getSelectedBookmarks(adapter.selectedItems)
                }
                sendUrl(items, BROWSER_LOAD_URL_TAB_NEW)
            }
            R.id.openAllBg -> {
                val items = if (item is BookmarkFolder) {
                    item.itemList
                } else {
                    getSelectedBookmarks(adapter.selectedItems)
                }
                sendUrl(items, BROWSER_LOAD_URL_TAB_BG)
            }
            R.id.moveAllBookmark -> {
                val bookmarkItems = getSelectedBookmarks(adapter.selectedItems)
                adapter.isMultiSelectMode = false
                BookmarkFoldersDialog(activity, mManager)
                        .setTitle(R.string.move_bookmark)
                        .setCurrentFolder(mCurrentFolder, bookmarkItems)
                    .setOnFolderSelectedListener { folder ->
                            mManager.moveAll(mCurrentFolder, folder, bookmarkItems)

                            mManager.save()
                            adapter.notifyDataSetChanged()
                            false
                        }
                        .show()
            }
            R.id.deleteAllBookmark -> AlertDialog.Builder(activity)
                    .setTitle(R.string.confirm)
                    .setMessage(R.string.confirm_delete_bookmark)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val selectedList = getSelectedBookmarks(adapter.selectedItems)

                        mManager.removeAll(mCurrentFolder, selectedList)
                        mManager.save()

                        adapter.isMultiSelectMode = false
                        adapter.notifyDataSetChanged()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
        }
    }

    override fun onSelectionStateChange(items: Int) {
        actionMode?.title = getString(R.string.accessibility_toolbar_multi_select, items)
    }

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        mode.menuInflater.inflate(R.menu.bookmark_action_mode, menu)
        actionMode = mode
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        return false
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.openAllNew -> {
                val items = getSelectedBookmarks(adapter.selectedItems)
                sendUrl(items, BROWSER_LOAD_URL_TAB_NEW)
                return true
            }
            R.id.openAllBg -> {
                val items = getSelectedBookmarks(adapter.selectedItems)
                sendUrl(items, BROWSER_LOAD_URL_TAB_BG)
                return true
            }
            R.id.selectAll -> {
                for (i in 0 until adapter.itemCount) {
                    adapter.setSelect(i, true)
                }
                return true
            }
            R.id.moveAllBookmark -> {
                val bookmarkItems = getSelectedBookmarks(adapter.selectedItems)
                BookmarkFoldersDialog(activity as Context, mManager)
                        .setTitle(R.string.move_bookmark)
                        .setCurrentFolder(mCurrentFolder, bookmarkItems)
                    .setOnFolderSelectedListener { folder ->
                            mManager.moveAll(mCurrentFolder, folder, bookmarkItems)

                            mManager.save()
                            mode.finish()
                            false
                        }
                        .show()
                return true
            }
            R.id.deleteAllBookmark -> {
                AlertDialog.Builder(activity)
                        .setTitle(R.string.confirm)
                        .setMessage(R.string.confirm_delete_bookmark)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            val selectedList = getSelectedBookmarks(adapter.selectedItems)

                            mManager.removeAll(mCurrentFolder, selectedList)
                            mManager.save()

                            mode.finish()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                return true
            }
        }
        return false
    }

    override fun onDestroyActionMode(mode: ActionMode) {
        (activity as LongPressFixActivity).onDestroyActionMode()
        adapter.isMultiSelectMode = false
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (AppPrefs.saveBookmarkFolder.get()) {
            AppPrefs.saveBookmarkFolderId.set(mCurrentFolder.id)
            AppPrefs.commit(requireContext(), AppPrefs.saveBookmarkFolderId)
        }
        searchMenuItem = null
        optionsMenu = null
        viewBinding = null
    }

    private inner class Touch : ItemTouchHelper.Callback() {

        override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
            return makeFlag(ItemTouchHelper.ACTION_STATE_DRAG, ItemTouchHelper.DOWN or ItemTouchHelper.UP)
        }

        override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            adapter.move(viewHolder.adapterPosition, target.adapterPosition)
            mManager.save()
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

        override fun isLongPressDragEnabled(): Boolean {
            return adapter.isSortMode
        }
    }

    private fun showBreadCrumbs(show: Boolean) {
        binding.apply {
            if (show) {
                if (subBar.childCount == 0) {
                    subBar.addView(breadCrumbsView)
                }
            } else {
                if (subBar.childCount == 1) {
                    subBar.removeView(breadCrumbsView)
                }
            }
            showBreadCrumb = show
            setTitle(mCurrentFolder)
            if (show) {
                toolbarAppbar.elevation = 0f
            } else {
                toolbarAppbar.elevation = subBar.elevation
            }
        }
    }

    private fun setTitle(folder: BookmarkFolder) {
        val actionBar = (activity as AppCompatActivity).supportActionBar ?: return
        if (showBreadCrumb) {
            actionBar.title = ""
        } else {
            if (folder.title.isNullOrEmpty()) {
                actionBar.setTitle(R.string.bookmark)
            } else {
                actionBar.title = folder.title
            }
        }
    }

    private fun getFirstPosition(arguments: Bundle): BookmarkFolder {
        var id = arguments.getLong(ITEM_ID)
        if (AppPrefs.saveBookmarkFolder.get() || id > 0) {
            if (id < 1) {
                id = AppPrefs.saveBookmarkFolderId.get()
            }
            val item = mManager[id]
            if (item is BookmarkFolder) {
                return item
            }
        }
        return root
    }

    private fun getCrumb(folder: BookmarkFolder): BookmarkCrumb {
        val title = folder.title
        return BookmarkCrumb(folder, if (title.isNullOrEmpty()) getString(R.string.bookmark) else title)
    }

    private fun addAllFolderCrumbs(target: BookmarkFolder) {
        val parent = target.parent
        if (parent != null) addAllFolderCrumbs(parent)
        breadcrumbAdapter.addItem(getCrumb(target))
    }

    private class BookmarkCrumb(val folder: BookmarkFolder, override val title: String) : BreadcrumbsView.Breadcrumb {
        override fun equals(other: Any?): Boolean {
            return other is BookmarkCrumb && other.folder == folder
        }

        override fun hashCode(): Int {
            return folder.hashCode()
        }
    }

    companion object {
        private const val MODE_PICK = "pick"
        private const val ITEM_ID = "id"
        private const val ICON_ALPHA = 190

        operator fun invoke(pickMode: Boolean, id: Long): BookmarkFragment {
            val fragment = BookmarkFragment()
            fragment.arguments = Bundle().apply {
                putBoolean(MODE_PICK, pickMode)
                putLong(ITEM_ID, id)
            }
            return fragment
        }
    }
}
