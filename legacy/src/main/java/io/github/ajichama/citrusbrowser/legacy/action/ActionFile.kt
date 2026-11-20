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

package io.github.ajichama.citrusbrowser.legacy.action

import android.content.Context
import android.util.AtomicFile
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import io.github.ajichama.citrusbrowser.core.utility.log.ErrorReport
import io.github.ajichama.citrusbrowser.core.utility.log.Logger
import okio.Okio
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.io.IOException
import java.io.Serializable

abstract class ActionFile : Serializable {

    abstract fun getFile(context: Context): File

    abstract fun reset()

    fun load(context: Context): Boolean {
        reset()

        val file = getFile(context)
        if (!file.exists() && !File(file.path + ".bak").exists()) return true

        try {
            JsonReader.of(AtomicFile(file).openRead().source().buffer()).use {
                if (!load(it)) {
                    Logger.e(TAG, "loadMain error (return false)")
                    return false
                }
                return true
            }
        } catch (e: IOException) {
            ErrorReport.printAndWriteLog(e)
        }

        return false
    }

    @Throws(IOException::class)
    abstract fun load(reader: JsonReader): Boolean

    fun write(context: Context): Boolean {
        val file = getFile(context)

        try {
            val buffer = okio.Buffer()
            JsonWriter.of(buffer).use {
                if (!write(it)) {
                    Logger.e(TAG, "writeMain error (return false)")
                    return false
                }
            }
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try { output.write(buffer.readByteArray()); atomic.finishWrite(output) }
            catch (error: Exception) { atomic.failWrite(output); throw error }
            return true
        } catch (e: IOException) {
            ErrorReport.printAndWriteLog(e)
        }
        return false
    }

    @Throws(IOException::class)
    abstract fun write(writer: JsonWriter): Boolean

    companion object {
        private const val serialVersionUID = 9159377694255234638L

        private const val TAG = "ActionFile"
    }
}
