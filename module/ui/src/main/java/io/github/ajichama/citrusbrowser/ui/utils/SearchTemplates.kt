package io.github.ajichama.citrusbrowser.ui.utils

import java.net.URLEncoder

/** 既定値と検索語の扱いを検索画面・共有Intent・アドレス入力で揃える。 */
object SearchTemplates {
    const val DEFAULT_GOOGLE_URL = "https://www.google.com/search?q=%s"

    private val legacyDefaults = setOf(
        "http://www.google.com/m?q=%s",
        "https://www.google.co.jp/m?q=%s",
    )

    @JvmStatic
    fun normalizeLegacyDefault(template: String): String =
        if (template in legacyDefaults) DEFAULT_GOOGLE_URL else template

    @JvmStatic
    fun compose(query: String, template: String, placeholder: String): String {
        val index = if (placeholder.isNotEmpty()) template.indexOf(placeholder) else -1
        if (index < 0) return template
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        // URLUtilと同じく最初のplaceholderだけを置換し、既存テンプレートの解釈を変えない。
        return template.substring(0, index) + encoded + template.substring(index + placeholder.length)
    }
}
