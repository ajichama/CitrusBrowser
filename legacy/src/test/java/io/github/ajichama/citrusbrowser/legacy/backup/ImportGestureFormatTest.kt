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

import org.junit.Assert.*
import org.junit.Test
import java.io.*

class ImportGestureFormatTest {
    private val sample = ImportGestureFormat.Sample(99, listOf(ImportGestureFormat.Stroke(listOf(
        ImportGestureFormat.Point(1f, 2f, 3), ImportGestureFormat.Point(4f, 5f, 6)))))
    private fun binary(block: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { DataOutputStream(it).use(block) }.toByteArray()

    @Test fun completeV1FormatRoundTripsWithSeparateActionAndGestureIds() {
        val entries = listOf(ImportGestureFormat.Entry(7, listOf(sample)))
        val bytes = binary { writeShort(1); writeInt(1); writeUTF("7"); writeInt(1); writeLong(99); writeInt(1); writeInt(2)
            writeFloat(1f); writeFloat(2f); writeLong(3); writeFloat(4f); writeFloat(5f); writeLong(6) }
        assertEquals(entries, ImportGestureFormat.decode(bytes))
        assertArrayEquals(bytes, ImportGestureFormat.encode(entries))
    }
    @Test fun emptyLibraryAndSinglePointStrokeAreValid() {
        assertEquals(emptyList<Any>(), ImportGestureFormat.decode(binary { writeShort(1); writeInt(0) }))
        val entry = ImportGestureFormat.Entry(0, listOf(sample.copy(strokes = listOf(ImportGestureFormat.Stroke(listOf(sample.strokes[0].points[0]))))))
        assertEquals(listOf(entry), ImportGestureFormat.decode(ImportGestureFormat.encode(listOf(entry))))
    }
    @Test fun badHeaderHugeAndNegativeCountsAreRejectedBeforeAllocating() {
        listOf(binary { writeShort(2); writeInt(0) }, binary { writeShort(1); writeInt(Int.MAX_VALUE) },
            binary { writeShort(1); writeInt(-1) }, binary { writeShort(1); writeInt(1); writeUTF("0"); writeInt(Int.MAX_VALUE) },
            binary { writeShort(1); writeInt(1); writeUTF("0"); writeInt(1); writeLong(0); writeInt(Int.MAX_VALUE) },
            binary { writeShort(1); writeInt(1); writeUTF("0"); writeInt(1); writeLong(0); writeInt(1); writeInt(Int.MAX_VALUE) })
            .forEach { bytes -> assertThrows(IOException::class.java) { ImportGestureFormat.decode(bytes) } }
    }
    @Test fun zeroStrokeOrPointAndInvalidEntryNamesAreRejected() {
        listOf(binary { writeShort(1); writeInt(1); writeUTF("0"); writeInt(1); writeLong(0); writeInt(0) },
            binary { writeShort(1); writeInt(1); writeUTF("0"); writeInt(1); writeLong(0); writeInt(1); writeInt(0) },
            binary { writeShort(1); writeInt(1); writeUTF("not-an-id"); writeInt(0) })
            .forEach { bytes -> assertThrows(IOException::class.java) { ImportGestureFormat.decode(bytes) } }
    }
    @Test fun truncatedOrTrailingBytesAndNonFiniteCoordinatesAreRejected() {
        val bytes = ImportGestureFormat.encode(listOf(ImportGestureFormat.Entry(1, listOf(sample))))
        assertThrows(IOException::class.java) { ImportGestureFormat.decode(bytes.copyOf(bytes.size - 1)) }
        assertThrows(IOException::class.java) { ImportGestureFormat.decode(bytes + byteArrayOf(1)) }
        val bad = sample.copy(strokes = listOf(ImportGestureFormat.Stroke(listOf(ImportGestureFormat.Point(Float.NaN, 1f, 0)))))
        assertThrows(IOException::class.java) { ImportGestureFormat.encode(listOf(ImportGestureFormat.Entry(1, listOf(bad)))) }
    }
    @Test fun duplicateEntryOrInternalGestureIdsAreRejected() {
        val entry = ImportGestureFormat.Entry(1, listOf(sample))
        assertThrows(IOException::class.java) { ImportGestureFormat.encode(listOf(entry, entry.copy(actionId = 2))) }
        assertThrows(IOException::class.java) { ImportGestureFormat.encode(listOf(entry, entry)) }
    }
    @Test fun missingGesturePairIsRejectedAndCompletePairsAreAccepted() {
        assertThrows(IOException::class.java) { ImportGestureFormat.requirePairs(setOf("app_gestures1/0.lib.dat")) }
        assertThrows(IOException::class.java) { ImportGestureFormat.requirePairs(setOf("app_gestures1/1.list.dat")) }
        ImportGestureFormat.requirePairs(setOf("app_gestures1/0.lib.dat", "app_gestures1/0.list.dat"))
    }
    @Test fun shapeIgnoresTimestampAndInternalIdButKeepsCoordinates() {
        val later = sample.copy(id = 100, strokes = sample.strokes.map { stroke -> stroke.copy(points = stroke.points.map { it.copy(time = it.time + 100) }) })
        assertEquals(ImportGestureFormat.shape(sample), ImportGestureFormat.shape(later))
        assertNotEquals(ImportGestureFormat.shape(sample), ImportGestureFormat.shape(later.copy(strokes = listOf(ImportGestureFormat.Stroke(listOf(ImportGestureFormat.Point(8f, 9f, 0)))))))
    }
    @Test fun mergingKeepsExistingShapeActionsAndRemapsCollidingNewGestureIds() {
        val current = ImportGestureFormat.Entry(7, listOf(sample))
        val different = sample.copy(strokes = listOf(ImportGestureFormat.Stroke(listOf(ImportGestureFormat.Point(8f, 9f, 0)))))
        val source = ImportGestures.PairData(0, listOf(ImportGestureFormat.Entry(7, listOf(sample.copy(id = 100), different))), mapOf(7L to "[[1020,null]]".toByteArray()), 0)
        var actions = 0
        val result = ImportGestures.merge(source, listOf(current), { it }) { actions++; 42L }
        val decoded = ImportGestureFormat.decode(result.bytes)
        assertEquals(current, decoded.first()); assertEquals(42L, decoded.last().actionId)
        assertEquals(1, result.added); assertEquals(1, result.duplicates); assertEquals(1, actions)
        assertNotEquals(sample.id, decoded.last().samples.single().id)
    }
}
