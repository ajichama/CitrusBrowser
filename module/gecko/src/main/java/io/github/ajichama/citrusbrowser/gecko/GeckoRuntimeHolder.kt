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

package io.github.ajichama.citrusbrowser.gecko

import android.content.Context
import android.os.Looper
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

/**
 * プロセス内で唯一の GeckoRuntime を所有する。
 *
 * Gecko はプロセスごとに1回だけ初期化でき、`GeckoRuntime.create` は稼働中の Gecko があると失敗する。
 * そのため Runtime の設定（profile を含む）は、このプロセスで最初に取得する前にしか決められない。
 * 通常起動は従来どおり `GeckoRuntime.getDefault` を使い、既定 profile と debug 用設定ファイルの扱いを変えない。
 */
object GeckoRuntimeHolder {
    private var runtime: GeckoRuntime? = null
    private var reservedSettings: GeckoRuntimeSettings? = null

    /** 計測用の設定を予約したプロセスか。予約後は既定 profile の Runtime へ戻さない。 */
    @Volatile
    var customized: Boolean = false
        private set

    val isCreated: Boolean get() = runtime != null

    fun obtain(context: Context): GeckoRuntime {
        checkMainThread()
        runtime?.let { return it }
        val settings = reservedSettings
        // 予約後に生成が失敗しても既定 profile へ切り替えず、例外のまま呼出し側へ返す。
        val created = if (settings == null) {
            GeckoRuntime.getDefault(context.applicationContext)
        } else {
            GeckoRuntime.create(context.applicationContext, settings)
        }
        runtime = created
        return created
    }

    /**
     * 計測専用。このプロセスで最初に作る Runtime の設定を予約する。
     * 既に Runtime がある場合は失敗させ、利用者の profile を使う Runtime を黙って共有しない。
     */
    fun reserveFirstRuntimeSettings(settings: GeckoRuntimeSettings) {
        checkMainThread()
        check(runtime == null) { "このプロセスでは既に GeckoRuntime が作成済みです。別プロセスで実行してください。" }
        check(reservedSettings == null) { "GeckoRuntime の設定は既に予約済みです。" }
        reservedSettings = settings
        customized = true
    }

    private fun checkMainThread() = check(Looper.myLooper() === Looper.getMainLooper()) {
        "GeckoRuntime はメインスレッドで扱ってください。"
    }
}
