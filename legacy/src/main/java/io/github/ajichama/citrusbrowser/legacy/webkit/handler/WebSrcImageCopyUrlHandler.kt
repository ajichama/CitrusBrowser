package io.github.ajichama.citrusbrowser.legacy.webkit.handler

import android.content.Context
import io.github.ajichama.citrusbrowser.legacy.utils.extensions.setClipboardWithToast
import java.lang.ref.WeakReference

class WebSrcImageCopyUrlHandler(context: Context) : WebSrcImageHandler() {
    private val mReference: WeakReference<Context> = WeakReference(context)

    override fun handleUrl(url: String) {
        mReference.get()?.run {
            setClipboardWithToast(url)
        }
    }
}
