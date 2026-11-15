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
import android.os.Handler
import android.os.Looper
import android.view.*
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import dagger.hilt.android.AndroidEntryPoint
import io.github.ajichama.citrusbrowser.adblock.R
import io.github.ajichama.citrusbrowser.adblock.filter.abp.isNeedUpdate
import io.github.ajichama.citrusbrowser.adblock.filter.unified.getFilterDir
import io.github.ajichama.citrusbrowser.adblock.repository.abp.AbpDatabase
import io.github.ajichama.citrusbrowser.adblock.repository.abp.AbpEntity
import io.github.ajichama.citrusbrowser.adblock.repository.abp.AbpFilterStore
import io.github.ajichama.citrusbrowser.adblock.service.AbpUpdateService
import io.github.ajichama.citrusbrowser.core.utility.utils.ui
import io.github.ajichama.citrusbrowser.ui.extensions.applyIconColor
import io.github.ajichama.citrusbrowser.ui.widget.recycler.OnRecyclerListener
import javax.inject.Inject

@AndroidEntryPoint
class AbpFragment : Fragment(), OnRecyclerListener, AddAbpDialog.OnAddItemListener, AbpMenuDialog.OnAbpMenuListener, AbpItemDeleteDialog.OnAbpItemDeleteListener {

    @Inject
    internal lateinit var abpDatabase: AbpDatabase

    private lateinit var adapter: AbpEntityAdapter
    private var reloadGeneration = 0

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
        ui {
            abpDatabase.abpDao().update(entity)
            reloadFromDatabase()
            if (entity.enabled && entity.isNeedUpdate()) {
                AbpUpdateService.update(appContext, entity, result)
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
        ui {
            if (entity.entityId > 0) {
                abpDatabase.abpDao().update(entity)
            } else {
                entity.entityId = abpDatabase.abpDao().inset(entity).toInt()
            }
            reloadFromDatabase()
            AbpUpdateService.update(appContext, entity, result)
        }
    }

    override fun onAskDelete(index: Int, entity: AbpEntity) {
        AbpItemDeleteDialog(index, entity).show(childFragmentManager, "delete")
    }

    override fun onEdit(index: Int, entity: AbpEntity) {
        AddAbpDialog.create(entity).show(childFragmentManager, "edit")
    }

    override fun onRefresh(index: Int, entity: AbpEntity) {
        AbpUpdateService.update(requireContext(), entity, result)
    }

    override fun onDelete(index: Int, entity: AbpEntity) {
        val appContext = context?.applicationContext ?: return
        reloadGeneration++
        ui {
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
                AbpUpdateService.updateAll(requireContext(), true, result)
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private val result = object : AbpUpdateService.UpdateResult(Handler(Looper.getMainLooper())) {
        override fun onUpdated(entity: AbpEntity) {
            reloadFromDatabase()
        }

        override fun onFailedUpdate(entity: AbpEntity) {
            reloadFromDatabase()
        }

        override fun onUpdateAll() {
            reloadFromDatabase()
        }
    }

    private fun reloadFromDatabase() {
        val generation = ++reloadGeneration
        if (!isAdded || view == null) return
        ui {
            val current = abpDatabase.abpDao().getAll()
            if (generation != reloadGeneration || !isAdded || view == null) return@ui
            // 通信開始時の古い entity を差し戻さず、削除・URL 編集・無効化の現在値を表示する。
            adapter.items.clear()
            adapter.items.addAll(current)
            adapter.notifyDataSetChanged()
        }
    }
}
