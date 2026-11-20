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
package io.github.ajichama.citrusbrowser.legacy.settings.data

import android.content.Context
import io.github.ajichama.citrusbrowser.ui.settings.AppPrefs

/**
 * CitrusBrowser で初期値を変えた設定を、保存済みの設定（Yuzu Browser から取り込んだものを含む）にも1回だけ反映する。
 * AppPrefs は全項目を保存するため、初期値を変えるだけでは既存の設定に届かない。
 * 1回だけ行い、その後で利用者が戻した値は変えない。設定を取り込んだときは、もう一度だけ行う。
 */
object DefaultsMigration {
    private const val PREFS = "citrus_defaults_migration"
    private const val KEY_2026_11 = "closed_tab_and_popup_applied"

    @JvmStatic
    fun migrate(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_2026_11, false)) return
        // 閉じたタブを開き直せるようにし、利用者の操作によらないポップアップを止める（Issue #10・#11）。
        AppPrefs.save_closed_tab.set(true)
        AppPrefs.web_popup.set(false)
        AppPrefs.commit(context, AppPrefs.save_closed_tab, AppPrefs.web_popup)
        prefs.edit().putBoolean(KEY_2026_11, true).apply()
    }

    /** 設定の取り込み後に、次の起動でもう一度反映する。 */
    @JvmStatic
    fun resetAfterImport(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_2026_11).apply()
    }
}
