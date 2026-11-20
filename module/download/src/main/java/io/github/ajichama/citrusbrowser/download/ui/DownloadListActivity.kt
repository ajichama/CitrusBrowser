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

package io.github.ajichama.citrusbrowser.download.ui

import android.os.Bundle
import dagger.hilt.android.AndroidEntryPoint
import io.github.ajichama.citrusbrowser.download.R
import io.github.ajichama.citrusbrowser.download.core.data.DownloadFileInfo
import io.github.ajichama.citrusbrowser.download.service.connection.ActivityClient
import io.github.ajichama.citrusbrowser.download.service.connection.ServiceSocket
import io.github.ajichama.citrusbrowser.download.ui.fragment.DownloadListFragment
import io.github.ajichama.citrusbrowser.ui.INTENT_EXTRA_MODE_FULLSCREEN
import io.github.ajichama.citrusbrowser.ui.INTENT_EXTRA_MODE_ORIENTATION
import io.github.ajichama.citrusbrowser.ui.app.ThemeActivity
import io.github.ajichama.citrusbrowser.ui.settings.AppPrefs

@AndroidEntryPoint
class DownloadListActivity : ThemeActivity(), ActivityClient.ActivityClientListener, DownloadCommandController {

    private lateinit var downloadService: ServiceSocket

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.fragment_base)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        var fullscreen = AppPrefs.fullscreen.get()
        var orientation = AppPrefs.oritentation.get()
        if (intent != null) {
            fullscreen = intent.getBooleanExtra(INTENT_EXTRA_MODE_FULLSCREEN, fullscreen)
            orientation = intent.getIntExtra(INTENT_EXTRA_MODE_ORIENTATION, orientation)
        }
        if (fullscreen) {
            setStatusBarHidden(true)
        }
        requestedOrientation = orientation

        downloadService = ServiceSocket(this, this)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                    .replace(R.id.container, DownloadListFragment(), TAG)
                    .commit()
        }
    }

    override fun onResume() {
        super.onResume()
        downloadService.bindService()
    }

    override fun onPause() {
        super.onPause()
        downloadService.unbindService()
    }

    override fun update(info: DownloadFileInfo) {
        val fragment = supportFragmentManager.findFragmentByTag(TAG)
        if (fragment is ActivityClient.ActivityClientListener) {
            fragment.update(info)
        }
    }

    override fun getDownloadInfo(list: List<DownloadFileInfo>) {
        val fragment = supportFragmentManager.findFragmentByTag(TAG)
        if (fragment is ActivityClient.ActivityClientListener) {
            fragment.getDownloadInfo(list)
        }
    }

    override fun cancelDownload(id: Long) {
        downloadService.cancelDownload(id)
    }

    override fun pauseDownload(id: Long) {
        downloadService.pauseDownload(id)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return super.onSupportNavigateUp()
    }

    companion object {
        private const val TAG = "main"
    }
}
