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

package io.github.ajichama.citrusbrowser.adblock.repository

import android.content.Context
import android.content.SharedPreferences
import java.io.IOException

/**
 * AdBlock フィルタ更新時刻設定の SharedPreferences ラッパー。
 * 旧 kvs-schema (JCenter廃止に伴い削除) が生成していた AdBlockPref と完全互換のテーブル・キー構造を保持。
 */
class AdBlockPref private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(TABLE_NAME, Context.MODE_PRIVATE)

    var abpNextUpdateTime: Long
        get() = prefs.getLong(KEY_NEXT_UPDATE_TIME, -1L)
        set(value) = prefs.edit().putLong(KEY_NEXT_UPDATE_TIME, value).apply()

    var abpLastUpdateTime: Long
        get() = prefs.getLong(KEY_LAST_UPDATE_TIME, -1L)
        set(value) = prefs.edit().putLong(KEY_LAST_UPDATE_TIME, value).apply()

    var defaultSubscriptionsRevision: Int
        get() = prefs.getInt(KEY_DEFAULT_SUBSCRIPTIONS_REVISION, 0)
        set(value) {
            // DB 登録後に確定し、保存失敗時は次回の移行をやり直す。
            val previous = defaultSubscriptionsRevision
            if (!prefs.edit().putInt(KEY_DEFAULT_SUBSCRIPTIONS_REVISION, value).commit()) {
                // commit 失敗時にもメモリ上の値は変わるため、再試行できる値に戻す。
                prefs.edit().putInt(KEY_DEFAULT_SUBSCRIPTIONS_REVISION, previous).apply()
                throw IOException("既定購読の移行版を保存できませんでした")
            }
        }

    companion object {
        private const val TABLE_NAME = "abp"
        private const val KEY_NEXT_UPDATE_TIME = "abpNextUpdateTime"
        private const val KEY_LAST_UPDATE_TIME = "abpLastUpdateTime"
        private const val KEY_DEFAULT_SUBSCRIPTIONS_REVISION = "defaultSubscriptionsRevision"

        @JvmStatic
        fun get(context: Context): AdBlockPref = AdBlockPref(context)
    }
}
