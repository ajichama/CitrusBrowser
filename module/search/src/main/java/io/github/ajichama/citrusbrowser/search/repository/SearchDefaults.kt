package io.github.ajichama.citrusbrowser.search.repository

import android.content.Context
import com.squareup.moshi.Moshi
import io.github.ajichama.citrusbrowser.search.model.provider.SearchSettings
import io.github.ajichama.citrusbrowser.ui.utils.SearchTemplates

/** 旧初期Google項目と識別できるものだけを移行し、ユーザー編集済み項目は保持する。 */
object SearchDefaults {
    fun normalize(settings: SearchSettings): SearchSettings {
        val items = settings.items.map { entry ->
            if (entry.id == 1 && entry.title == "Google" && entry.color == 0xff2196f3.toInt() && entry.isUseFavicon) {
                entry.copy(url = SearchTemplates.normalizeLegacyDefault(entry.url))
            } else entry
        }
        return if (items == settings.items) settings else settings.copy(items = items)
    }

    /** 通常起動とインポート反映後で共用する。全prefsの再読込や初期値の再作成は行わない。 */
    @JvmStatic
    fun migrateLegacyDefaults(context: Context, moshi: Moshi) {
        SearchUrlManager(context, moshi).load()
    }
}
