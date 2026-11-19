/*
 * Copyright 2020 Hazuki
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

package io.github.ajichama.citrusbrowser.adblock.core

import android.net.Uri

data class ContentRequest(
    val url: Uri,
    val pageUrl: Uri,
    val type: Int,
    val isThirdParty: Boolean,
    /** `$method=` の照合に使う HTTP メソッド。 */
    val method: String = "GET",
) {
    companion object {
        const val TYPE_OTHER = 0x01
        const val TYPE_SCRIPT = 0x02
        const val TYPE_IMAGE = 0x04
        const val TYPE_STYLE_SHEET = 0x08
        const val TYPE_SUB_DOCUMENT = 0x10
        const val TYPE_DOCUMENT = 0x20
        const val TYPE_MEDIA = 0x40
        const val TYPE_FONT = 0x80
        const val TYPE_POPUP = 0x0100
        const val TYPE_WEB_SOCKET = 0x0200
        const val TYPE_XHR = 0x0400

        const val TYPE_NETWORK_ALL = 0x0000_ffff
        const val FLAG_IMPORTANT = 0x1000_0000
        const val FLAG_BADFILTER = 0x0800_0000
        /** `$redirect-rule=`: それ自体では遮断せず、別のルールで遮断したときに返す資源だけを決める。 */
        const val FLAG_REDIRECT_RULE = 0x0001_0000
        internal const val REDIRECT_MASK = 0x0700_0000
        internal const val REDIRECT_EMPTY = 0x0100_0000
        internal const val REDIRECT_SCRIPT = 0x0200_0000
        internal const val REDIRECT_STYLE = 0x0300_0000
        internal const val REDIRECT_IMAGE = 0x0400_0000
        internal const val REDIRECT_FRAME = 0x0500_0000

        const val TYPE_ELEMENT_HIDE = 0x4000_0000
        const val TYPE_ELEMENT_GENERIC_HIDE = 0x2000_0000
    }
}
