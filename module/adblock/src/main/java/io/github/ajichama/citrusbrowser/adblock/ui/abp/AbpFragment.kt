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

package io.github.ajichama.citrusbrowser.adblock.ui.abp

import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.floatingactionbutton.FloatingActionButton
import dagger.hilt.android.AndroidEntryPoint
import io.github.ajichama.citrusbrowser.adblock.R
import io.github.ajichama.citrusbrowser.adblock.filter.abp.isNeedUpdate
import io.github.ajichama.citrusbrowser.adblock.filter.unified.getFilterDir
import io.github.ajichama.citrusbrowser.adblock.repository.abp.AbpDatabase
import io.github.ajichama.citrusbrowser.adblock.repository.abp.AbpEntity
import io.github.ajichama.citrusbrowser.adblock.repository.abp.AbpFilterStore
import io.github.ajichama.citrusbrowser.adblock.service.AbpUpdateScheduler
import io.github.ajichama.citrusbrowser.ui.extensions.applyIconColor
import io.github.ajichama.citrusbrowser.ui.widget.recycler.OnRecyclerListener
import javax.inject.Inject
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

@AndroidEntryPoint
class AbpFragment : Fragment(), OnRecyclerListener, AddAbpDialog.OnAddItemListener, AbpMenuDialog.OnAbpMenuListener, AbpItemDeleteDialog.OnAbpItemDeleteListener {

    @Inject
    internal lateinit var abpDatabase: AbpDatabase

    private lateinit var adapter: AbpEntityAdapter
    private var reloadGeneration = 0
    private val workModel: AbpWorkViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_abp_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val activity = activity ?: throw IllegalStateException()
        val recyclerView: RecyclerView = view.findViewById(R.id.recyclerView)
        val fab: FloatingActionButton = view.findViewById(R.id.fab)
        adapter = AbpEntityAdapter(activity, mutableListOf(), this@AbpFragment)
        reloadFromDatabase()
        recyclerView.run {
            layoutManager = LinearLayoutManager(activity)
            adapter = this@AbpFragment.adapter
        }
        fab.setOnClickListener {
            AddAbpDialog.create(null)
                    .show(childFragmentManager, "edit")
        }
        val workManager = WorkManager.getInstance(requireContext().applicationContext)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // view が作り直されても、SavedState の実 ID から終端の観測を再接続する。
                reloadFromDatabase()
                launch { workModel.errors.collect { Toast.makeText(requireContext(), R.string.abp_update_failed, Toast.LENGTH_SHORT).show() } }
                launch {
                    // enqueue 完了と SavedState 保存の間に終了した場合も、永続タグから再接続する。
                    workManager.getWorkInfosByTagFlow(AbpUpdateScheduler.MANUAL_TAG).collect { infos ->
                        infos.filterNot { it.state.isFinished }.forEach { info ->
                            val scope = info.tags.firstOrNull { it.startsWith(AbpUpdateScheduler.SCOPE_TAG) }
                                ?.removePrefix(AbpUpdateScheduler.SCOPE_TAG)?.toIntOrNull()
                            if (scope != null) workModel.adopt(scope, info.id)
                        }
                    }
                }
                workModel.pending.collectLatest { pending ->
                    coroutineScope {
                        pending.forEach { token -> launch {
                            val id = UUID.fromString(token.substringAfter(':'))
                            val info = workManager.getWorkInfoByIdFlow(id).first { it == null || it.state.isFinished }
                            if (workModel.finish(token)) {
                                reloadFromDatabase()
                                val message = when {
                                    info == null -> R.string.abp_update_expired
                                    info.state != WorkInfo.State.SUCCEEDED -> R.string.abp_update_failed
                                    info.outputData.getString(AbpUpdateScheduler.KEY_OUTCOME) == "Skipped" -> R.string.abp_update_skipped
                                    else -> R.string.abp_update_completed
                                }
                                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                            }
                        } }
                    }
                }
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.abp_fragment, menu)
        applyIconColor(menu)
    }

    override fun onRecyclerItemClicked(v: View, position: Int) {
        val appContext = context?.applicationContext ?: return
        reloadGeneration++
        val entity = adapter.items[position]
        entity.enabled = !entity.enabled
        lifecycleScope.launch {
            abpDatabase.abpDao().setEnabled(entity.entityId, entity.enabled)
            reloadFromDatabase()
            if (entity.enabled && entity.isNeedUpdate()) {
                enqueueUpdate(appContext, entity.entityId)
            }
        }
        adapter.notifyItemChanged(position)
    }

    override fun onRecyclerItemLongClicked(v: View, position: Int): Boolean {
        AbpMenuDialog(position, adapter.items[position])
                .show(childFragmentManager, "menu")
        return true
    }

    override fun onAddEntity(entity: AbpEntity) {
        val appContext = context?.applicationContext ?: return
        reloadGeneration++
        lifecycleScope.launch {
            if (entity.entityId > 0) {
                val previousUrl = abpDatabase.abpDao().getById(entity.entityId)?.url
                if (abpDatabase.abpDao().updateUrl(entity.entityId, entity.url) > 0) {
                    // 旧 root 形式には sourceUrl が無いため、URL 編集時は残存 cache も無効化する。
                    if (previousUrl != null) AbpFilterStore(appContext.getFilterDir()).invalidatePreviousSource(entity.entityId, previousUrl)
                }
            } else {
                entity.entityId = abpDatabase.abpDao().inset(entity).toInt()
            }
            reloadFromDatabase()
            enqueueUpdate(appContext, entity.entityId)
        }
    }

    override fun onAskDelete(index: Int, entity: AbpEntity) {
        AbpItemDeleteDialog(index, entity).show(childFragmentManager, "delete")
    }

    override fun onEdit(index: Int, entity: AbpEntity) {
        AddAbpDialog.create(entity).show(childFragmentManager, "edit")
    }

    override fun onRefresh(index: Int, entity: AbpEntity) {
        enqueueUpdate(requireContext().applicationContext, entity.entityId)
    }

    override fun onDelete(index: Int, entity: AbpEntity) {
        val appContext = context?.applicationContext ?: return
        reloadGeneration++
        lifecycleScope.launch {
            abpDatabase.abpDao().delete(entity)
            val dir = appContext.getFilterDir()
            AbpFilterStore(dir).delete(entity.entityId)
            val i = adapter.items.indexOf(entity)
            if (i >= 0) {
                adapter.items.removeAt(i)
                adapter.notifyItemRemoved(i)
            }
            reloadFromDatabase()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.update -> {
                enqueueUpdate(requireContext().applicationContext, AbpUpdateScheduler.ALL)
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun enqueueUpdate(context: android.content.Context, entityId: Int) {
        workModel.enqueue(entityId) { AbpUpdateScheduler.manualAwait(context, entityId) }
    }

    private fun reloadFromDatabase() {
        val generation = ++reloadGeneration
        if (!isAdded || view == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            val current = abpDatabase.abpDao().getAll()
            if (generation != reloadGeneration || !isAdded || view == null) return@launch
            // 通信開始時の古い entity を差し戻さず、削除・URL 編集・無効化の現在値を表示する。
            adapter.items.clear()
            adapter.items.addAll(current)
            adapter.notifyDataSetChanged()
        }
    }
}
