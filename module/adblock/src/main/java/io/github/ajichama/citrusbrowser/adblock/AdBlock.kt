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

package io.github.ajichama.citrusbrowser.adblock

import android.net.Uri
import android.webkit.WebResourceRequest
import io.github.ajichama.citrusbrowser.adblock.core.ContentRequest
import io.github.ajichama.citrusbrowser.adblock.core.RequestClassification

const val BROADCAST_ACTION_UPDATE_AD_BLOCK_DATA = "io.github.ajichama.citrusbrowser.adblock.broadcast.update.adblock"

fun WebResourceRequest.isThirdParty(pageUri: Uri): Boolean {
    return RequestClassification.isThirdParty(url.host, pageUri.host)
}


fun WebResourceRequest.getContentRequest(pageUri: Uri) =
    ContentRequest(url, pageUri, RequestClassification.contentType(isForMainFrame, url.scheme, url.path, requestHeaders), isThirdParty(pageUri))
