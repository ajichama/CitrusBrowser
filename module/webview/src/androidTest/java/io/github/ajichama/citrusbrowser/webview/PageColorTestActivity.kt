package io.github.ajichama.citrusbrowser.webview

import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout

class PageColorTestActivity : Activity() {
    lateinit var container: FrameLayout
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = FrameLayout(this)
        setContentView(container)
    }
}
