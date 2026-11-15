package io.github.ajichama.citrusbrowser.core.utility.utils

import java.net.URLDecoder
import java.net.URLEncoder

// 保存済み Intent URI の移行だけに使う旧識別子。通常の実装は新名前空間を使う。
internal const val LEGACY_BROWSER_NAMESPACE = "jp.hazuki.yuzubrowser"
private const val BROWSER_NAMESPACE = "io.github.ajichama.citrusbrowser"

/** URL 本文や extra の値を変えず、自アプリへ向けた保存済み Intent の識別子だけを移行する。 */
fun migrateLegacyBrowserIntentUri(source: String): String {
    val marker = source.lastIndexOf("#Intent;")
    if (marker < 0) return source
    val contentStart = marker + "#Intent;".length
    val fields = source.substring(contentStart).split(';').toMutableList()
    if (fields.lastOrNull() != "end") return source

    var sectionStart = 0
    fields.indices.forEach { index ->
        if (fields[index] == "SEL" || fields[index] == "end") {
            migrateIntentSection(fields, sectionStart, index)
            sectionStart = index + 1
        }
    }
    return source.substring(0, contentStart) + fields.joinToString(";")
}

private fun migrateIntentSection(fields: MutableList<String>, start: Int, end: Int) {
    fun fieldValue(name: String): String? = (start until end).asSequence()
        .map { fields[it] }.firstOrNull { it.startsWith("$name=") }
        ?.substringAfter('=')?.decodeIntentIdentifier()

    val component = fieldValue("component")
    val targetPackage = fieldValue("package")
    val action = fieldValue("action")
    val internal = when {
        component != null -> component.substringBefore('/').isBrowserPackage()
        targetPackage != null -> targetPackage.isBrowserPackage()
        else -> action?.startsWith("$LEGACY_BROWSER_NAMESPACE.") == true
    }
    if (!internal) return

    for (index in start until end) {
        val field = fields[index]
        val separator = field.indexOf('=')
        if (separator < 0) continue
        val key = field.substring(0, separator)
        val rawValue = field.substring(separator + 1)
        if (key == "component" || key == "package" || key == "action") {
            val value = rawValue.decodeIntentIdentifier() ?: continue
            val migrated = when (key) {
                "component" -> {
                    val slash = value.indexOf('/')
                    if (slash < 0) value else value.substring(0, slash).migrateBrowserPrefix() +
                        "/" + value.substring(slash + 1).migrateBrowserPrefix()
                }
                else -> value.migrateBrowserPrefix()
            }
            if (migrated != value) fields[index] = "$key=${migrated.encodeIntentIdentifier()}"
        } else if (key.length > 2 && key[1] == '.' && key[0] in "SBbcdfils") {
            val extraKey = key.substring(2).decodeIntentIdentifier() ?: continue
            // 名前空間内でも、既知の extra 命名規則以外のユーザー任意キーは保持する。
            if (extraKey.startsWith("$LEGACY_BROWSER_NAMESPACE.") && extraKey.contains(".extra.")) {
                fields[index] = "${key.take(2)}${extraKey.migrateBrowserPrefix().encodeIntentIdentifier()}=$rawValue"
            }
        }
    }
}

private fun String.isBrowserPackage(): Boolean = this == LEGACY_BROWSER_NAMESPACE ||
    this == "$LEGACY_BROWSER_NAMESPACE.debug" || this == BROWSER_NAMESPACE || this == "$BROWSER_NAMESPACE.debug"

private fun String.migrateBrowserPrefix(): String = when {
    this == LEGACY_BROWSER_NAMESPACE -> BROWSER_NAMESPACE
    startsWith("$LEGACY_BROWSER_NAMESPACE.") -> BROWSER_NAMESPACE + substring(LEGACY_BROWSER_NAMESPACE.length)
    else -> this
}

private fun String.decodeIntentIdentifier(): String? = try {
    // Intent URI の '+' はフォームデータと異なり、そのままの文字として扱う。
    URLDecoder.decode(replace("+", "%2B"), "UTF-8")
} catch (_: IllegalArgumentException) {
    null
}

private fun String.encodeIntentIdentifier(): String = URLEncoder.encode(this, "UTF-8").replace("+", "%20")
