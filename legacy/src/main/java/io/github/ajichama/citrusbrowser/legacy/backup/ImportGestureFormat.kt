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
package io.github.ajichama.citrusbrowser.legacy.backup

import java.io.*

/** AOSP GestureStore v1。framework の count に基づく配列確保より先に、全階層の上限を検証する。 */
internal object ImportGestureFormat {
    data class Point(val x: Float, val y: Float, val time: Long)
    data class Stroke(val points: List<Point>)
    data class Sample(val id: Long, val strokes: List<Stroke>)
    data class Entry(val actionId: Long, val samples: List<Sample>)

    fun decode(bytes: ByteArray): List<Entry> {
        if (bytes.size > 8 * 1024 * 1024) throw IOException("ジェスチャーが大きすぎます。")
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            fun count(max: Int, min: Int = 0): Int = input.readInt().also {
                if (it !in min..max) throw IOException("ジェスチャーの件数が上限を超えています。")
            }
            if (input.readShort().toInt() != 1) throw IOException("未対応のジェスチャー形式です。")
            var points = 0
            var samples = 0
            val names = hashSetOf<Long>()
            val ids = hashSetOf<Long>()
            val result = List(count(512)) {
                val name = input.readUTF()
                val action = name.toLongOrNull()?.takeIf { it >= 0 && name == it.toString() }
                    ?: throw IOException("ジェスチャーの操作 ID が不正です。")
                if (!names.add(action)) throw IOException("ジェスチャーの操作 ID が重複しています。")
                val gestures = List(count(2048, 1)) {
                    if (++samples > 2048) throw IOException("ジェスチャーが多すぎます。")
                    val id = input.readLong()
                    if (!ids.add(id)) throw IOException("ジェスチャー ID が重複しています。")
                    Sample(id, List(count(16, 1)) {
                        Stroke(List(count(4096, 1)) {
                            if (++points > 100_000) throw IOException("ジェスチャーの合計点数が上限を超えています。")
                            val x = input.readFloat()
                            val y = input.readFloat()
                            if (!x.isFinite() || !y.isFinite() || kotlin.math.abs(x) > 1_000_000 || kotlin.math.abs(y) > 1_000_000)
                                throw IOException("ジェスチャー座標が不正です。")
                            Point(x, y, input.readLong())
                        })
                    })
                }
                Entry(action, gestures)
            }
            if (input.read() != -1) throw IOException("ジェスチャー末尾に余分なデータがあります。")
            return result
        }
    }

    fun encode(entries: List<Entry>): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeShort(1); out.writeInt(entries.size)
            entries.forEach { entry ->
                out.writeUTF(entry.actionId.toString()); out.writeInt(entry.samples.size)
                entry.samples.forEach { sample ->
                    out.writeLong(sample.id); out.writeInt(sample.strokes.size)
                    sample.strokes.forEach { stroke ->
                        out.writeInt(stroke.points.size)
                        stroke.points.forEach { point -> out.writeFloat(point.x); out.writeFloat(point.y); out.writeLong(point.time) }
                    }
                }
            }
        }
    }.toByteArray().also { decode(it) }

    /** 時刻と内部 ID の違いでは、同じ線を重複登録しない。 */
    fun shape(sample: Sample): List<List<Pair<Float, Float>>> = sample.strokes.map { stroke -> stroke.points.map { it.x to it.y } }

    fun requirePairs(paths: Set<String>) {
        for (id in 0..1) {
            val base = "app_gestures1/$id"
            if (("$base.lib.dat" in paths) != ("$base.list.dat" in paths)) throw IOException("ジェスチャーの線と操作の組が欠けています。")
        }
    }
}
