/*
 * Copyright (C) 2017-2021 Hazuki
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

package io.github.ajichama.citrusbrowser

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.webkit.WebView
import androidx.appcompat.app.AppCompatDelegate
import com.squareup.moshi.Moshi
import dagger.hilt.android.HiltAndroidApp
import io.github.ajichama.citrusbrowser.adblock.registerAdBlockNotification
import io.github.ajichama.citrusbrowser.adblock.repository.abp.AbpDatabase
import io.github.ajichama.citrusbrowser.core.utility.log.Logger
import io.github.ajichama.citrusbrowser.core.utility.utils.createLanguageConfig
import io.github.ajichama.citrusbrowser.download.registerDownloadNotification
import io.github.ajichama.citrusbrowser.legacy.settings.data.AppData
import io.github.ajichama.citrusbrowser.provider.ProviderManager
import io.github.ajichama.citrusbrowser.ui.BrowserApplication
import io.github.ajichama.citrusbrowser.ui.settings.AppPrefs
import javax.inject.Inject

@HiltAndroidApp
class CitrusBrowserApplication : Application(), BrowserApplication {

    override val applicationId = BuildConfig.APPLICATION_ID
    override val permissionAppSignature = PERMISSION_MYAPP_SIGNATURE
    override val browserState = BrowserStateImpl()
    override val providerManager = ProviderManager()
    override val context: Context
        get() = this

    @Inject
    override lateinit var moshi: Moshi

    @Inject
    lateinit var abpDatabase: AbpDatabase

    override fun onCreate() {
        super.onCreate()
        registerDownloadNotification()
        registerAdBlockNotification()

        Logger.d(TAG, "onCreate()")
        browserState.isNeedLoad = false
        ErrorReportServer.initialize(this)
        AppData.init(this, moshi, abpDatabase)
        // WorkManager は主プロセスでだけ自動で初期化される。再起動用の別プロセス（:restart_process）で
        // 更新を予約するとアプリが落ちるため、主プロセスに限る。
        if (isMainProcess()) io.github.ajichama.citrusbrowser.adblock.service.AbpUpdateScheduler.initialize(this)
        ErrorReportServer.setDetailedLog(AppPrefs.detailed_log.get())
        if (AppPrefs.slow_rendering.get()) {
            WebView.enableSlowWholeDocumentDraw()
        }
        Logger.isDebug = BuildConfig.DEBUG
    }

    private fun isMainProcess(): Boolean {
        val name = if (android.os.Build.VERSION.SDK_INT >= 28) {
            Application.getProcessName()
        } else {
            runCatching { java.io.File("/proc/self/cmdline").readText().trimEnd(Char(0)) }.getOrNull()
        }
        return name.isNullOrEmpty() || name == packageName
    }

    override fun attachBaseContext(base: Context) {
        val lang = AppPrefs.language.get()
        val context = if (lang != "") {
            val config = base.createLanguageConfig(lang)
            ContextCompat(base.createConfigurationContext(config), base)
        } else {
            base
        }

        super.attachBaseContext(context)
    }

    private class ContextCompat(
        configContext: Context,
        private val baseActivityContext: Context
    ) : ContextWrapper(configContext) {

        override fun getSystemService(name: String): Any? {
            return baseActivityContext.getSystemService(name)
        }

        override fun getSystemServiceName(serviceClass: Class<*>): String? {
            return baseActivityContext.getSystemServiceName(serviceClass)
        }
    }

    companion object {
        private const val TAG = "CitrusBrowserApplication"
        const val PERMISSION_MYAPP_SIGNATURE = BuildConfig.APPLICATION_ID + ".permission.myapp.signature"
        lateinit var instance: CitrusBrowserApplication

        init {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }
    }
}
