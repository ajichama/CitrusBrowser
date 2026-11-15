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

package io.github.ajichama.citrusbrowser.adblock.repository.abp

import androidx.room.*

@Dao
interface AbpDao {

    @Query("SELECT * from abp")
    suspend fun getAll(): List<AbpEntity>

    @Insert
    suspend fun inset(abpEntity: AbpEntity): Long

    @Insert
    suspend fun inset(entities: List<AbpEntity>)

    @Delete
    suspend fun delete(abpEntity: AbpEntity)

    @Update
    suspend fun update(abpEntity: AbpEntity)

    @Query("SELECT * FROM abp WHERE entityId = :id")
    suspend fun getById(id: Int): AbpEntity?

    // 更新中の購読無効化や URL 編集を、取得開始時の古いオブジェクトで上書きしない。
    @Query("""UPDATE abp SET title = :title, lastUpdate = :lastUpdate,
        lastLocalUpdate = :lastLocalUpdate, expires = :expires, version = :version,
        homePage = :homePage, lastModified = :lastModified
        WHERE entityId = :id AND url = :expectedUrl""")
    suspend fun updateMetadata(id: Int, expectedUrl: String, title: String?, lastUpdate: String?,
                               lastLocalUpdate: Long, expires: Int, version: String?,
                               homePage: String?, lastModified: String?): Int

    @Query("UPDATE abp SET lastLocalUpdate = :time WHERE entityId = :id AND url = :expectedUrl")
    suspend fun markChecked(id: Int, expectedUrl: String, time: Long): Int
}
