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

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * カメラの画像の明るさ（YUV の Y 面）から QR コードを読み取る（#18）。
 * 画面から切り離し、単体テストで確かめられるようにしている。1つの解析スレッドから使う。
 */
internal class QrCodeDecoder {
    private val reader = QRCodeReader()
    private val hints = mapOf(DecodeHintType.CHARACTER_SET to "UTF-8")

    /**
     * [luminance] は1行が [rowStride] バイトの明るさの並び。読み取れなければ null を返す。
     * 白黒が反転した QR コードも、反転して読み直す。
     */
    fun decode(luminance: ByteArray, rowStride: Int, width: Int, height: Int): String? {
        require(rowStride >= width && luminance.size >= rowStride * height) { "画像の大きさが合いません" }
        val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)
        return decode(BinaryBitmap(HybridBinarizer(source)))
            ?: decode(BinaryBitmap(HybridBinarizer(source.invert())))
    }

    private fun decode(bitmap: BinaryBitmap): String? = try {
        reader.decode(bitmap, hints).text?.takeIf { it.isNotEmpty() }
    } catch (_: ReaderException) {
        null
    } finally {
        reader.reset()
    }
}
