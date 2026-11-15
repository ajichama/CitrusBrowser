package io.github.ajichama.citrusbrowser.core.utility.utils

/** 旧版で保存された内部 URL の読み込み互換をここへ集約する。 */
object BrowserSchemeCompat {
    private const val LEGACY_SCHEME = "yuzu"
    const val SCHEME_PATTERN = "(?:citrus|$LEGACY_SCHEME)"

    @JvmStatic
    fun normalizeScheme(scheme: String?): String? =
        if (scheme.equals(LEGACY_SCHEME, ignoreCase = true)) "citrus" else scheme

    @JvmStatic
    fun normalizeUrl(url: String): String {
        val colon = url.indexOf(':')
        return if (colon >= 0 && url.substring(0, colon).equals(LEGACY_SCHEME, ignoreCase = true)) {
            "citrus" + url.substring(colon)
        } else {
            url
        }
    }
}
