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

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import io.github.ajichama.citrusbrowser.search.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * カメラで QR コードを読み取り、読み取った文字列を結果として返す画面（#18）。
 * 読み取った URL は開かず、呼び出し元（検索の画面）の入力欄に入れて、確定は利用者に任せる。
 * カメラの権限は、この画面を開いたときに求める。拒否されたら、知らせて閉じる。
 */
class QrScanActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var analysisExecutor: ExecutorService
    private val decoder = QrCodeDecoder()
    private val delivered = AtomicBoolean(false)

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            startCamera()
        } else {
            closeWithMessage(R.string.qr_scan_permission_denied)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.qr_scan_activity)
        previewView = findViewById(R.id.previewView)
        analysisExecutor = Executors.newSingleThreadExecutor()
        setResult(RESULT_CANCELED)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else if (savedInstanceState == null) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (isFinishing || isDestroyed) return@addListener
            try {
                val provider = future.get()
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy(ANALYSIS_SIZE,
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build())
                    .build()
                analysis.setAnalyzer(analysisExecutor) { analyze(it) }
                // 背面カメラがない端末（タブレットの一部など）では、前面カメラを使う。
                val selector = if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    CameraSelector.DEFAULT_BACK_CAMERA
                } else {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                }
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview, analysis)
            } catch (e: Exception) {
                Log.w(TAG, "カメラを起動できませんでした", e)
                closeWithMessage(R.string.qr_scan_camera_unavailable)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(image: ImageProxy) {
        image.use {
            if (delivered.get()) return
            // Y 面（明るさ）だけを使う。QR コードは向きによらず読めるので、回転は補正しない。
            val plane = it.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val data = ByteArray(rowStride * it.height)
            buffer.rewind()
            buffer.get(data, 0, minOf(buffer.remaining(), data.size))
            val text = decoder.decode(data, rowStride, it.width, it.height) ?: return
            if (delivered.compareAndSet(false, true)) {
                runOnUiThread { deliver(text) }
            }
        }
    }

    private fun deliver(text: String) {
        if (isFinishing) return
        setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT, text))
        finish()
    }

    private fun closeWithMessage(messageId: Int) {
        Toast.makeText(applicationContext, messageId, Toast.LENGTH_LONG).show()
        finish()
    }

    companion object {
        /** 読み取った文字列（String）。 */
        const val EXTRA_RESULT = "io.github.ajichama.citrusbrowser.search.QrScanActivity.extra.result"

        private const val TAG = "QrScanActivity"
        private val ANALYSIS_SIZE = Size(1280, 720)
    }
}
