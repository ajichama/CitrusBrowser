/*
 * Copyright (C) 2026 CitrusBrowser contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.github.ajichama.citrusbrowser.engine

/** 今回の契約を通して呼べる操作だけを列挙する。エンジンの全機能を表す一覧ではない。 */
enum class EngineCapability { GO_BACK, GO_FORWARD, RELOAD, STOP }

/** 取得した時点の状態。読込中かどうかを進捗率から推測しない。 */
data class EngineNavigationState(
    val canGoBack: Boolean,
    val canGoForward: Boolean,
    val isLoading: Boolean
)

/**
 * UI スレッドから使う最小の操作境界。Android や特定エンジンの型を公開しない。
 * 操作の呼出完了はページ読込成功を意味しない。認証・POST再送等の既存経路は所有者に残す。
 * この段階では View の生成・破棄、保存、進捗購読を所有せず、タブ所有者の寿命に従う。
 */
interface EngineSession {
    val capabilities: Set<EngineCapability>
    val state: EngineNavigationState
    fun goBack()
    fun goForward()
    fun reload()
    fun stopLoading()
}
