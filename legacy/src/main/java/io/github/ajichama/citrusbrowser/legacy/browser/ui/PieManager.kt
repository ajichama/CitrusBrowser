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

package io.github.ajichama.citrusbrowser.legacy.browser.ui

import android.content.Context
import android.view.ViewGroup
import io.github.ajichama.citrusbrowser.core.utility.extensions.density
import io.github.ajichama.citrusbrowser.core.utility.extensions.getResColor
import io.github.ajichama.citrusbrowser.legacy.R
import io.github.ajichama.citrusbrowser.legacy.action.Action
import io.github.ajichama.citrusbrowser.legacy.action.manager.ActionController
import io.github.ajichama.citrusbrowser.legacy.action.manager.ActionIconManager
import io.github.ajichama.citrusbrowser.legacy.action.manager.QuickControlActionManager
import io.github.ajichama.citrusbrowser.legacy.utils.view.pie.PieItem
import io.github.ajichama.citrusbrowser.legacy.utils.view.pie.PieMenu
import io.github.ajichama.citrusbrowser.ui.settings.AppPrefs
import io.github.ajichama.citrusbrowser.ui.theme.ThemeData

class PieManager(private val context: Context, private val actionController: ActionController, private val iconManager: ActionIconManager) : PieMenu.PieController {

    private val itemSize = context.resources.getDimension(R.dimen.qc_item_size).toInt()
    private val pie = PieMenu(context).apply {
        setController(this@PieManager)
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    val isOpen: Boolean
        get() = pie.isOpen

    fun attachToLayout(layout: ViewGroup) {
        layout.addView(pie)
        makeItems()
    }

    fun detachFromLayout(layout: ViewGroup) {
        layout.removeView(pie)
        pie.clearItems()
    }

    private fun makeItems() {
        val manager = QuickControlActionManager.getInstance(context)

        for (action in manager.level1.list)
            addItem(action, 1)
        for (action in manager.level2.list)
            addItem(action, 2)
        for (action in manager.level3.list)
            addItem(action, 3)
    }

    private fun addItem(action: Action, l: Int): PieItem {
        val item = PieItem(context, itemSize, action, actionController, iconManager, l)
        pie.addItem(item)
        return item
    }

    override fun onOpen(): Boolean {
        pie.notifyChangeState()
        return true
    }

    fun onPreferenceReset() {
        val density = context.density
        pie.setRadiusStart((AppPrefs.qc_rad_start.get() * density + 0.5f).toInt())
        pie.setRadiusIncrement((AppPrefs.qc_rad_inc.get() * density + 0.5f).toInt())
        pie.setSlop((AppPrefs.qc_slop.get() * density + 0.5f).toInt())
        pie.setPosition(AppPrefs.qc_position.get())
    }

    fun onThemeChanged(themeData: ThemeData?) {
        if (themeData != null && themeData.qcItemBackgroundColorNormal != 0)
            pie.setNormalColor(themeData.qcItemBackgroundColorNormal)
        else
            pie.setNormalColor(context.getResColor(R.color.qc_normal))

        if (themeData != null && themeData.qcItemBackgroundColorSelect != 0)
            pie.setSelectedColor(themeData.qcItemBackgroundColorSelect)
        else
            pie.setSelectedColor(context.getResColor(R.color.qc_selected))

        if (themeData != null && themeData.qcItemColor != 0)
            pie.setColorFilterToItems(themeData.qcItemColor)
        else
            pie.setColorFilterToItems(0)
    }
}
