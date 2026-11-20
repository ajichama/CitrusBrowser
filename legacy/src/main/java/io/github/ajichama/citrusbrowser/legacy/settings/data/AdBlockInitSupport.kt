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

package io.github.ajichama.citrusbrowser.legacy.settings.data

import android.content.Context
import io.github.ajichama.citrusbrowser.adblock.repository.abp.AbpDefaultSubscriptions
import io.github.ajichama.citrusbrowser.adblock.repository.abp.AbpDatabase
import kotlinx.coroutines.runBlocking

fun initAbpFilter(context: Context, abpDatabase: AbpDatabase) = runBlocking {
    AbpDefaultSubscriptions.initialize(context, abpDatabase)
}

fun disableCitrusList(abpDatabase: AbpDatabase) = runBlocking {
    val dao = abpDatabase.abpDao()
    dao.getAll().filter { it.url == "citrus://adblock/filter" }.forEach {
        dao.setEnabled(it.entityId, false)
    }
}
