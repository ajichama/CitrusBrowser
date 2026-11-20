/*
 * Copyright (C) 2026 CitrusBrowser contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package io.github.ajichama.citrusbrowser.legacy.action.manager

import android.content.Context
import io.github.ajichama.citrusbrowser.legacy.action.Action
import io.github.ajichama.citrusbrowser.legacy.action.ActionList
import io.github.ajichama.citrusbrowser.legacy.action.SingleAction
import io.github.ajichama.citrusbrowser.legacy.action.item.CustomMenuSingleAction

/**
 * 保存済みの長押しメニュー（Yuzu Browser から取り込んだものを含む）に、後から加えた項目を足す。
 * 1回だけ行い、利用者がその後で項目を外しても戻さない。設定を取り込んだときは、もう一度だけ行う。
 */
object LongPressMenuMigration {
    private const val PREFS = "long_press_menu_migration"
    private const val KEY_COPY_IMAGE = "copy_image_added"

    @JvmStatic
    fun migrate(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_COPY_IMAGE, false)) return
        val manager = LongPressActionManager.getInstance(context)
        val changed = listOf(manager.image.action, manager.image_link.action).map(::insertCopyImage).any { it }
        if (changed) manager.save(context)
        prefs.edit().putBoolean(KEY_COPY_IMAGE, true).apply()
    }

    /** 設定の取り込み後に、次の起動でもう一度足すようにする。 */
    @JvmStatic
    fun resetAfterImport(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_COPY_IMAGE).apply()
    }

    /** 独自メニューに「画像の URL をコピー」があり「画像をコピー」がなければ、その前に足す。足したら true。 */
    fun insertCopyImage(action: Action): Boolean {
        var changed = false
        action.filterIsInstance<CustomMenuSingleAction>().forEach { menu ->
            val list: ActionList = menu.actionList
            if (list.any { it.hasId(SingleAction.LPRESS_COPY_IMAGE) }) return@forEach
            val index = list.indexOfFirst { it.hasId(SingleAction.LPRESS_COPY_IMAGE_URL) }
            if (index < 0) return@forEach
            list.add(index, Action(SingleAction.makeInstance(SingleAction.LPRESS_COPY_IMAGE)))
            changed = true
        }
        return changed
    }

    private fun Action.hasId(id: Int) = any { it.id == id }
}
