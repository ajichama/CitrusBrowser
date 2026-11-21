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

package io.github.ajichama.citrusbrowser.search.presentation.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** カメラの Y 面に見立てた明るさの並びから、QR コードを読み取れることを確かめる（#18）。 */
class QrCodeDecoderTest {

    private fun luminance(text: String, size: Int, rowStride: Int, inverted: Boolean = false): ByteArray {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size,
            mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 4))
        // 行の終わりに余白（rowStride − 幅）を持つ、実際のカメラの画像と同じ並びにする。余白は中間の灰色で埋める。
        val data = ByteArray(rowStride * size) { 0x80.toByte() }
        for (y in 0 until size) {
            for (x in 0 until size) {
                val dark = matrix[x, y] != inverted
                data[y * rowStride + x] = if (dark) 0 else 0xFF.toByte()
            }
        }
        return data
    }

    @Test fun readsUrlFromImageWithRowPadding() {
        val url = "https://example.com/path?q=citrus&lang=ja"
        val result = QrCodeDecoder().decode(luminance(url, 240, rowStride = 256), 256, 240, 240)
        assertEquals(url, result)
    }

    @Test fun readsJapaneseText() {
        val text = "柚子と蜜柑のブラウザ"
        assertEquals(text, QrCodeDecoder().decode(luminance(text, 240, 240), 240, 240, 240))
    }

    @Test fun readsInvertedCode() {
        val url = "https://example.org/"
        assertEquals(url, QrCodeDecoder().decode(luminance(url, 240, 240, inverted = true), 240, 240, 240))
    }

    @Test fun returnsNullWhenNoCode() {
        val blank = ByteArray(200 * 200) { 0x80.toByte() }
        assertNull(QrCodeDecoder().decode(blank, 200, 200, 200))
    }

    @Test fun canDecodeRepeatedly() {
        val decoder = QrCodeDecoder()
        assertNull(decoder.decode(ByteArray(100 * 100), 100, 100, 100))
        assertEquals("first", decoder.decode(luminance("first", 200, 200), 200, 200, 200))
        assertEquals("second", decoder.decode(luminance("second", 200, 200), 200, 200, 200))
    }
}
