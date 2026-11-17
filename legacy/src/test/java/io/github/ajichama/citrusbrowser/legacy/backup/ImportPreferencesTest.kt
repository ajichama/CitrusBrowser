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

package io.github.ajichama.citrusbrowser.legacy.backup

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ImportPreferencesTest {
    @Test fun legacyMapPreservesTypesEscapesAndStringSets() {
        val result = ImportPreferences.parse("""<?xml version="1.0" encoding="utf-8"?><map><int name="i" value="3"/><long name="l" value="12345678900"/><float name="f" value="1.5"/><boolean name="b" value="true"/><string name="s">日本語&amp;&lt;</string><set name="set"><string>a</string><string>b</string></set></map>""".toByteArray())
        assertEquals(3, result["i"]); assertEquals(12345678900L, result["l"]); assertEquals(1.5f, result["f"])
        assertEquals(true, result["b"]); assertEquals("日本語&<", result["s"]); assertEquals(setOf("a", "b"), result["set"])
    }
    @Test fun duplicatesWrongRootsAndInvalidPrimitiveValuesAreRejected() {
        for (xml in listOf("<map><int name='a' value='1'/><int name='a' value='2'/></map>", "<other/>", "<map><boolean name='b' value='yes'/></map>", "<map><float name='f' value='NaN'/></map>"))
            assertThrows(IOException::class.java) { ImportPreferences.parse(xml.toByteArray()) }
        assertThrows(NumberFormatException::class.java) { ImportPreferences.parse("<map><int name='i' value='bad'/></map>".toByteArray()) }
    }
    @Test fun dtdAndExternalEntityDocumentsAreRejectedBeforeResolution() {
        val xml = """<!DOCTYPE map [<!ENTITY external SYSTEM "file:///never-read">]><map><string name="s">&external;</string></map>"""
        assertThrows(IOException::class.java) { ImportPreferences.parse(xml.toByteArray()) }
    }
    @Test fun incompleteOrMalformedUtf8InputIsRejected() {
        assertThrows(Exception::class.java) { ImportPreferences.parse("<map><string name='s'>incomplete".toByteArray()) }
        assertThrows(java.nio.charset.CharacterCodingException::class.java) { ImportPreferences.parse(byteArrayOf(0xff.toByte())) }
    }
}