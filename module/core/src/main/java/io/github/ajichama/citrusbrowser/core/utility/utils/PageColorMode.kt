package io.github.ajichama.citrusbrowser.core.utility.utils

/** 保存値とサイト別設定で共通のページ配色。アプリUIのテーマとは独立して扱う。 */
object PageColorMode {
    const val LIGHT = 0
    const val SITE_DARK = 1
    const val AUTO_DARK = 2
    const val INHERIT = -1
    private const val LEGACY_FORCE_DARK = 3

    @JvmStatic
    fun normalize(value: Int): Int = when (value) {
        SITE_DARK -> SITE_DARK
        AUTO_DARK, LEGACY_FORCE_DARK -> AUTO_DARK
        else -> LIGHT
    }

    @JvmStatic
    fun normalizeOverride(value: Int): Int =
        if (value in LIGHT..LEGACY_FORCE_DARK) normalize(value) else INHERIT

    fun isDark(value: Int): Boolean = normalize(value) != LIGHT
    fun allowsAlgorithmicDarkening(value: Int): Boolean = normalize(value) == AUTO_DARK
}
