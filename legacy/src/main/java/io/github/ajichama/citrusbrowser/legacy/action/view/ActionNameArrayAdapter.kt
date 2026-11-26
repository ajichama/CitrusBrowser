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

package io.github.ajichama.citrusbrowser.legacy.action.view

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.github.ajichama.citrusbrowser.legacy.R
import io.github.ajichama.citrusbrowser.legacy.action.ActionIconMap
import io.github.ajichama.citrusbrowser.legacy.action.ActionNameArray
import io.github.ajichama.citrusbrowser.legacy.action.SingleAction
import io.github.ajichama.citrusbrowser.legacy.utils.SearchText
import io.github.ajichama.citrusbrowser.ui.widget.recycler.OnRecyclerListener

class ActionNameArrayAdapter(
        context: Context,
        val nameArray: ActionNameArray,
        private val listener: OnRecyclerListener
) : RecyclerView.Adapter<ActionNameArrayAdapter.ViewHolder>() {
    // 選択は、絞り込みに関係なく元の一覧の位置（index）で持つ
    private val checked = BooleanArray(nameArray.actionList.size)
    // 表示している行（position）が、元の一覧のどの位置かの対応（#42 の絞り込み）
    private var visible = IntArray(nameArray.actionList.size) { it }
    private val inflater = LayoutInflater.from(context)
    private val icons = ActionIconMap(context.resources)
    private var mListener: OnSettingButtonListener? = null

    override fun getItemCount(): Int {
        return visible.size
    }

    override fun getItemId(position: Int): Long {
        return visible[position].toLong()
    }

    private fun getName(position: Int): String {
        return nameArray.actionList[visible[position]]!!
    }

    fun getItemValue(position: Int): Int {
        return nameArray.actionValues[visible[position]]
    }

    private fun getIcon(position: Int): Drawable? {
        return icons[nameArray.actionValues[visible[position]]]
    }

    fun isChecked(position: Int): Boolean {
        return checked[visible[position]]
    }

    /** 操作の名前で絞り込む（#42）。空なら全部を出す。選択はそのまま残る */
    fun filter(query: String) {
        val words = SearchText.words(query)
        val all = nameArray.actionList.indices
        visible = if (words.isEmpty()) {
            all.toList().toIntArray()
        } else {
            all.filter { SearchText.containsAll(nameArray.actionList[it] ?: "", words) }.toIntArray()
        }
        notifyDataSetChanged()
    }

    /** 元の一覧の位置が、今どの行に出ているか。絞り込みで隠れていれば -1 */
    fun positionOf(index: Int): Int {
        return visible.indexOf(index)
    }

    fun clearChoices() {
        for (i in checked.indices) {
            checked[i] = false
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(inflater.inflate(R.layout.select_action_item, parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.icon.setImageDrawable(getIcon(position))
        holder.text.text = getName(position)

        val checked = isChecked(position)

        holder.checkBox.isChecked = checked

        if (SingleAction.checkSubPreference(getItemValue(position))) {
            holder.setting.visibility = View.VISIBLE
            holder.setting.isEnabled = checked
            holder.setting.imageAlpha = if (checked) 0xff else 0x88
            holder.setting.setOnClickListener { mListener?.invoke(position) }
        } else {
            holder.setting.visibility = View.GONE
        }

        holder.itemView.setOnClickListener { listener.onRecyclerItemClicked(it, holder.adapterPosition) }
        holder.itemView.setOnLongClickListener { listener.onRecyclerItemLongClicked(it, holder.adapterPosition) }
    }

    fun toggleCheck(position: Int): Boolean {
        val index = visible[position]
        val newState = !checked[index]
        checked[index] = newState
        notifyItemChanged(position)
        return newState
    }

    /** 元の一覧の位置（index）で選択を変える。表示している行の位置ではない */
    fun setChecked(index: Int, value: Boolean) {
        checked[index] = value
    }

    fun setListener(mListener: OnSettingButtonListener) {
        this.mListener = mListener
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.iconImageView)
        val text: TextView = view.findViewById(R.id.nameTextView)
        val setting: ImageButton = view.findViewById(R.id.settingsButton)
        val checkBox: CheckBox = view.findViewById(R.id.checkBox)
    }
}
