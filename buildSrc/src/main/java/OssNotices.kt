import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.ArtifactCollection
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.*
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact
import org.w3c.dom.Element
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

// 通常生成は固定入力だけを読み、ネットワーク取得は明示更新タスクへ分離する。
@CacheableTask
abstract class GenerateOssNotices : DefaultTask() {
    @get:Input abstract val runtimeComponents: ListProperty<String>
    @get:Input abstract val documentTitle: org.gradle.api.provider.Property<String>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val fixedInputs: ConfigurableFileCollection
    @get:Internal abstract val inputRoot: DirectoryProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @get:OutputDirectory abstract val reportDirectory: DirectoryProperty

    init { documentTitle.convention("オープンソースライセンス") }

    @TaskAction fun generate() {
        val result = OssNotices.generate(inputRoot.get().asFile, runtimeComponents.get(), documentTitle.get())
        val report = reportDirectory.get().asFile.apply { mkdirs() }
        OssNotices.writeAtomic(File(report, "missing.txt"), result.missing.joinToString("\n", postfix = if (result.missing.isEmpty()) "" else "\n").toByteArray())
        OssNotices.writeAtomic(File(report, "components.txt"), runtimeComponents.get().distinct().sorted().joinToString("\n", postfix = "\n").toByteArray())
        if (result.missing.isNotEmpty()) throw GradleException("OSS通知の固定入力が不足しています: $report/missing.txt\n更新候補をレビューして固定入力へ反映してください。")
        // 不足・失敗時に不完全な通知を公開しない。旧出力は成功時だけ置換する。
        val assets = outputDirectory.get().asFile.apply { mkdirs() }
        OssNotices.writeAtomic(File(assets, "licenses.compressed")) { output ->
            GZIPOutputStream(output).use { it.write(result.html.toByteArray(Charsets.UTF_8)) }
        }
    }
}

abstract class CheckOssNotices : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val reports: ConfigurableFileCollection

    @TaskAction fun check() {
        val missing = reports.files.filter { it.isFile }.flatMap { it.readLines() }.filter { it.isNotBlank() }
        if (missing.isNotEmpty()) throw GradleException("OSS通知が不足しています:\n${missing.joinToString("\n")}\n更新候補をレビューし、固定入力へ反映してください。")
    }
}

abstract class UpdateOssNotices : DefaultTask() {
    @get:Internal lateinit var runtimeArtifacts: ArtifactCollection
    @get:Internal lateinit var dependencies: DependencyHandler
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @get:Input abstract val offline: org.gradle.api.provider.Property<Boolean>

    init {
        outputs.upToDateWhen { false }
        // 依存解決専用の明示操作。通常の生成タスクはconfiguration cacheにこの状態を持ち込まない。
        notCompatibleWithConfigurationCache("明示的なOSSメタデータ更新は公開Gradle依存解決APIを利用します。")
    }

    @TaskAction fun update() {
        val artifacts = runtimeArtifacts.artifacts
        val collector = OssMetadataCollector(dependencies, !offline.get())
        // platform/BOMのように配布物を持たない制約はアプリの通知対象へ含めない。
        val ids = artifacts.mapNotNull { it.id.componentIdentifier as? ModuleComponentIdentifier }
            .distinctBy { "${it.group}:${it.module}:${it.version}" }
        collector.collect(ids, artifacts, outputDirectory.get().asFile)
        logger.lifecycle("OSS通知の更新候補: ${outputDirectory.get().asFile}（固定入力は変更していません）")
    }
}

internal data class OssLicense(val name: String, val url: String)
internal data class OssDocument(val kind: String, val path: String, val sha256: String, val source: String)
internal data class OssComponent(
    val id: String, val name: String, val url: String,
    val licenses: List<OssLicense>, val documents: List<OssDocument>
)
internal data class OssGeneration(val html: String, val missing: List<String>)

internal class OssTextDownloadCache(private val fetch: (String) -> ByteArray?) {
    private val results = mutableMapOf<String, ByteArray?>()
    fun get(url: String): ByteArray? {
        // 失敗nullも保存し、同じURLのタイムアウトを依存件数分繰り返さない。
        if (!results.containsKey(url)) results[url] = fetch(url)
        return results[url]
    }
}

object OssRuntimeGraph {
    @JvmStatic fun coordinates(artifacts: Set<ResolvedArtifactResult>): List<String> = artifacts
        .mapNotNull { it.id.componentIdentifier as? ModuleComponentIdentifier }
        .map { "${it.group}:${it.module}:${it.version}" }.distinct().sorted()
}

internal object OssNotices {
    fun xml(file: File): Element = file.inputStream().use { xml(it) }

    fun xml(input: InputStream): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        return factory.newDocumentBuilder().parse(input).documentElement
    }

    fun children(parent: Element, name: String): List<Element> = (0 until parent.childNodes.length)
        .mapNotNull { parent.childNodes.item(it) as? Element }
        .filter { (it.localName ?: it.tagName) == name }

    fun text(parent: Element, name: String): String = children(parent, name).firstOrNull()?.textContent?.trim().orEmpty()
    fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun canonicalText(bytes: ByteArray): ByteArray {
        // Gitのautocrlfや取得元の改行に依存せず、本文とSHAをUTF-8/LFへ統一する。
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        return text.replace("\r\n", "\n").replace('\r', '\n').toByteArray(Charsets.UTF_8)
    }

    fun licenseTextUrl(url: String): String = when {
        url.matches(Regex("https?://(?:www\\.)?apache.org/licenses/LICENSE-2\\.0(?:\\.txt)?/?")) ||
            url == "https://api.github.com/licenses/apache-2.0" -> "https://www.apache.org/licenses/LICENSE-2.0.txt"
        else -> url
    }

    fun plainLicenseText(contentType: String, bytes: ByteArray): ByteArray {
        val mime = contentType.substringBefore(';').trim().lowercase()
        require(mime !in setOf("text/html", "application/xhtml+xml", "application/json", "text/json", "application/xml", "text/xml") &&
            !mime.endsWith("+json") && !mime.endsWith("+xml")) { "ライセンスURLが本文以外の形式を返しました。" }
        val canonical = canonicalText(bytes)
        val text = canonical.toString(Charsets.UTF_8)
        val prefix = text.trimStart().removePrefix("\uFEFF").trimStart().take(100).lowercase()
        require(prefix.isNotBlank() && text.none { it.code < 32 && it !in "\n\t" } && !prefix.startsWith("<") &&
            !prefix.startsWith("{") && !prefix.startsWith("[")) { "ライセンス本文がHTML/JSON/XML/バイナリまたは空です。" }
        return canonical
    }

    fun writeAtomic(file: File, bytes: ByteArray) = writeAtomic(file) { it.write(bytes) }

    fun writeAtomic(file: File, writer: (OutputStream) -> Unit) {
        file.parentFile.mkdirs()
        val temporary = Files.createTempFile(file.parentFile.toPath(), ".oss-", ".tmp")
        try {
            Files.newOutputStream(temporary).use(writer)
            Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
    fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    fun checkedFile(root: File, path: String): File {
        val base = root.toPath().toAbsolutePath().normalize()
        val target = base.resolve(path).normalize()
        require(target.startsWith(base) && target != base) { "通知入力がルート外を指しています: $path" }
        return target.toFile()
    }

    fun verifiedText(root: File, path: String, expected: String): String {
        val bytes = canonicalText(checkedFile(root, path).readBytes())
        require(expected.matches(Regex("[0-9a-f]{64}")) && hash(bytes) == expected) { "通知本文のSHA-256が一致しません: $path" }
        return bytes.toString(Charsets.UTF_8)
    }

    fun components(file: File): List<OssComponent> {
        val root = xml(file)
        require(root.tagName == "components" && root.getAttribute("version") == "1") { "未対応の通知metadata形式です。" }
        val items = children(root, "component").map { entry ->
            OssComponent(entry.getAttribute("id"), entry.getAttribute("name"), entry.getAttribute("url"),
                children(entry, "license").map { OssLicense(it.getAttribute("name"), it.getAttribute("url")) },
                children(entry, "document").map { OssDocument(it.getAttribute("kind"), it.getAttribute("path"), it.getAttribute("sha256"), it.getAttribute("source")) })
        }
        require(items.map { it.id }.distinct().size == items.size) { "通知metadataの座標が重複しています。" }
        return items
    }

    fun generate(root: File, ids: List<String>, title: String = "オープンソースライセンス"): OssGeneration {
        val entries = components(File(root, "oss/dependencies.xml")).associateBy { it.id }
        val missing = mutableListOf<String>()
        val body = StringBuilder()
        val sources = xml(File(root, "oss/sources.xml"))
        require(sources.tagName == "sources" && sources.getAttribute("version") == "1")
        val sourceEntries = children(sources, "source")
        require(sourceEntries.map { it.getAttribute("id") }.distinct().size == sourceEntries.size)
        sourceEntries.sortedBy { it.getAttribute("id") }.forEach {
            body.append(verifiedText(root, it.getAttribute("html"), it.getAttribute("sha256"))).append('\n')
        }
        for (id in ids.distinct().sorted()) {
            val component = entries[id]
            body.append("<section><h2>").append(escape(component?.name?.ifBlank { id } ?: id)).append("</h2>")
            body.append("<p>").append(escape(id)).append("</p>")
            if (component == null) {
                missing.add("$id: metadataなし")
                body.append("<p>ライセンス情報を準備中です。</p></section>\n")
                continue
            }
            appendLink(body, component.url)
            component.licenses.forEach { license ->
                body.append("<p>").append(escape(license.name)).append("</p>")
                appendLink(body, license.url)
            }
            var hasLicenseText = false
            component.documents.sortedWith(compareBy({ it.kind }, { it.source }, { it.sha256 }))
                .distinctBy { it.kind to it.sha256 }.forEach { doc ->
                val text = verifiedText(root, doc.path, doc.sha256)
                if (doc.kind == "LICENSE" && text.isNotBlank()) hasLicenseText = true
                body.append("<h3>").append(escape(doc.kind)).append("</h3><pre>").append(escape(text)).append("</pre>")
            }
            if (!hasLicenseText) missing.add("$id: LICENSE本文なし")
            body.append("</section>\n")
        }
        val css = canonicalText(File(root, "html/license/fixLicensesHtml.css").readBytes()).toString(Charsets.UTF_8)
        val escapedTitle = escape(title)
        val html = "<!doctype html><html lang=\"ja\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>$escapedTitle</title><style>body{font-family:sans-serif;line-height:1.5;margin:16px}section,.library{border-bottom:1px solid #aaa;padding:12px 0}h1,h2{font-size:1.2em}h3{font-size:1em}pre{white-space:pre-wrap;overflow-wrap:anywhere}input,label{display:none}$css</style></head><body><h1>$escapedTitle</h1>$body</body></html>\n"
        return OssGeneration(html, missing)
    }

    private fun appendLink(body: StringBuilder, url: String) {
        // 固定metadataでもリンク先はWeb URLだけに制限し、実行可能なschemeを出力しない。
        if (runCatching { URI(url).scheme?.lowercase() in setOf("http", "https") }.getOrDefault(false)) {
            body.append("<p><a href=\"").append(escape(url)).append("\">").append(escape(url)).append("</a></p>")
        }
    }

    fun writeComponents(file: File, entries: List<OssComponent>) {
        writeAtomic(file, buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<components version=\"1\">\n")
            entries.sortedBy { it.id }.forEach { entry ->
                append("  <component id=\"${escape(entry.id)}\" name=\"${escape(entry.name)}\" url=\"${escape(entry.url)}\">\n")
                entry.licenses.distinct().sortedWith(compareBy({ it.name }, { it.url })).forEach {
                    append("    <license name=\"${escape(it.name)}\" url=\"${escape(it.url)}\"/>\n")
                }
                entry.documents.distinct().sortedWith(compareBy({ it.kind }, { it.source }, { it.sha256 })).forEach {
                    append("    <document kind=\"${escape(it.kind)}\" path=\"${escape(it.path)}\" sha256=\"${it.sha256}\" source=\"${escape(it.source)}\"/>\n")
                }
                append("  </component>\n")
            }
            append("</components>\n")
        }.toByteArray(Charsets.UTF_8))
    }
}

internal class OssMetadataCollector(private val dependencies: DependencyHandler, private val allowDownloads: Boolean) {
    private data class Pom(val name: String, val url: String, val licenses: List<OssLicense>, val properties: Map<String, String>)
    private val poms = mutableMapOf<String, Pom?>()
    private val warnings = sortedSetOf<String>()
    private val downloaded = OssTextDownloadCache(::downloadUncached)

    fun collect(ids: List<ModuleComponentIdentifier>, artifacts: Set<ResolvedArtifactResult>, output: File) {
        require(!output.exists() || output.isDirectory)
        val textDir = File(output, "oss/texts").apply { mkdirs() }
        fun document(kind: String, bytes: ByteArray, source: String): OssDocument? {
            val canonical = try {
                OssNotices.canonicalText(bytes)
            } catch (_: java.nio.charset.CharacterCodingException) {
                warnings.add("$source: UTF-8として読めない本文です。")
                return null
            }
            if (canonical.toString(Charsets.UTF_8).isBlank()) {
                warnings.add("$source: 本文が空です。")
                return null
            }
            val sha = OssNotices.hash(canonical)
            OssNotices.writeAtomic(File(textDir, "$sha.txt"), canonical)
            return OssDocument(kind, "oss/texts/$sha.txt", sha, source)
        }
        val entries = ids.sortedBy { "${it.group}:${it.module}:${it.version}" }.map { id ->
            val gav = "${id.group}:${id.module}:${id.version}"
            val pom = pom(id.group, id.module, id.version, emptySet())
            val docs = mutableListOf<OssDocument>()
            artifacts.filter { it.id.componentIdentifier == id }.sortedBy { it.file.name }.forEach { artifact ->
                extractDocuments(artifact.file).forEach { (path, bytes) ->
                    document(if (path.substringAfterLast('/').startsWith("NOTICE", true)) "NOTICE" else "LICENSE", bytes, "$gav!/$path")?.let { docs.add(it) }
                }
            }
            // 埋込LICENSEが参照・追補だけの場合もあるため、宣言されたURLは全件候補取得する。
            // この経路は明示更新専用。通常生成は固定本文だけを使い、通信しない。
            pom?.licenses.orEmpty().forEach { license ->
                val url = OssNotices.licenseTextUrl(license.url)
                download(url)?.let { bytes -> document("LICENSE", bytes, url)?.let { docs.add(it) } }
            }
            if (docs.none { it.kind == "LICENSE" }) warnings.add("$gav: LICENSE本文を取得できませんでした。")
            OssComponent(gav, pom?.name?.ifBlank { id.module } ?: id.module, pom?.url.orEmpty(), pom?.licenses.orEmpty(), docs.distinct())
        }
        OssNotices.writeComponents(File(output, "oss/dependencies.xml"), entries)
        OssNotices.writeAtomic(File(output, "warnings.txt"), warnings.joinToString("\n", postfix = if (warnings.isEmpty()) "" else "\n").toByteArray())
    }

    private fun pom(group: String, module: String, version: String, seen: Set<String>): Pom? {
        val gav = "$group:$module:$version"
        if (poms.containsKey(gav)) return poms[gav]
        if (gav in seen || seen.size >= 12) {
            warnings.add("$gav: 親POMが循環しているか深すぎます。")
            return null
        }
        return try {
            // POM取得だけをこの公開APIへ閉じ、Gradle内部キャッシュの構造には依存しない。
            val query = dependencies.createArtifactResolutionQuery().forModule(group, module, version)
                .withArtifacts(MavenModule::class.java, MavenPomArtifact::class.java).execute()
            val files = query.resolvedComponents.flatMap { it.getArtifacts(MavenPomArtifact::class.java) }
                .filterIsInstance<ResolvedArtifactResult>()
            val file = files.singleOrNull()?.file ?: throw IllegalStateException("POM未解決")
            val root = OssNotices.xml(file)
            val ownProps = OssNotices.children(root, "properties").firstOrNull()?.let { props ->
                (0 until props.childNodes.length).mapNotNull { props.childNodes.item(it) as? Element }
                    .associate { (it.localName ?: it.tagName) to it.textContent.trim() }
            }.orEmpty()
            val parent = OssNotices.children(root, "parent").firstOrNull()?.let {
                val parentGroup = expand(OssNotices.text(it, "groupId"), ownProps)
                val parentModule = expand(OssNotices.text(it, "artifactId"), ownProps)
                val parentVersion = expand(OssNotices.text(it, "version"), ownProps)
                if (listOf(parentGroup, parentModule, parentVersion).any { value -> value.isBlank() || value.contains("\${") }) {
                    warnings.add("$gav: 親POMの座標を展開できません。")
                    null
                } else pom(parentGroup, parentModule, parentVersion, seen + gav)
            }
            val props = parent?.properties.orEmpty() + ownProps + mapOf(
                "project.groupId" to group, "pom.groupId" to group, "project.artifactId" to module,
                "pom.artifactId" to module, "project.version" to version, "pom.version" to version)
            val declared = OssNotices.children(root, "licenses").flatMap { OssNotices.children(it, "license") }
                .map { OssLicense(expand(OssNotices.text(it, "name"), props), expand(OssNotices.text(it, "url"), props)) }
            Pom(expand(OssNotices.text(root, "name"), props), expand(OssNotices.text(root, "url"), props),
                if (declared.isEmpty()) parent?.licenses.orEmpty() else declared, props).also { poms[gav] = it }
        } catch (error: Exception) {
            warnings.add("$gav: POM情報不足 (${error.javaClass.simpleName})")
            poms[gav] = null
            null
        }
    }

    private fun expand(source: String, props: Map<String, String>): String {
        var current = source
        repeat(8) {
            val next = Regex("\\$\\{([^}]+)}").replace(current) { props[it.groupValues[1]] ?: it.value }
            if (next == current) return current
            current = next
        }
        return current
    }

    private fun download(url: String): ByteArray? = downloaded.get(url)

    private fun downloadUncached(url: String): ByteArray? = run {
        if (url.isBlank() || url.contains("\${")) return@run null
        if (!allowDownloads) {
            warnings.add("$url: offlineのため本文取得を省略しました。")
            return@run null
        }
        try {
            // 既知のApache本文URLとGitHub APIの同一識別子だけを公式plain本文へ正規化する。
            var next = OssNotices.licenseTextUrl(url)
            repeat(6) {
                val uri = URI(next)
                require(uri.scheme in setOf("https", "http"))
                val connection = uri.toURL().openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 20_000
                    connection.instanceFollowRedirects = false
                    connection.setRequestProperty("User-Agent", "CitrusBrowser-OSS-Notices")
                    val status = connection.responseCode
                    if (status in setOf(301, 302, 303, 307, 308)) {
                        next = uri.resolve(connection.getHeaderField("Location") ?: error("転送先なし")).toString()
                    } else {
                        require(status == 200) { "HTTP $status" }
                        val bytes = connection.inputStream.use { limitedBytes(it) }
                        return@run OssNotices.plainLicenseText(connection.contentType.orEmpty(), bytes)
                    }
                } finally {
                    connection.disconnect()
                }
            }
            error("転送回数超過")
        } catch (error: Exception) {
            warnings.add("$url: 本文取得不足 (${error.javaClass.simpleName})")
            null
        }
    }

    companion object {
        private const val MAX_TEXT = 2 * 1024 * 1024
        private fun limitedBytes(input: InputStream): ByteArray {
            val bytes = input.readNBytes(MAX_TEXT + 1)
            require(bytes.size <= MAX_TEXT) { "通知本文が大きすぎます。" }
            return bytes
        }

        internal fun extractDocuments(
            file: File,
            maxExpandedBytes: Long = 64L * 1024 * 1024,
            maxRetainedBytes: Long = 16L * 1024 * 1024,
            maxEntries: Int = 30_000
        ): List<Pair<String, ByteArray>> {
            if (!file.isFile || file.extension.lowercase() !in setOf("jar", "aar", "zip")) return emptyList()
            val result = mutableListOf<Pair<String, ByteArray>>()
            // 通常のライブラリのclass/通知を許容しつつ、圧縮された巨大entryで更新を占有しない。
            // 件数・総展開量・保持量を外側/内側で共有し、入れ子はAAR直下のJAR一段だけ読む。
            var expandedBytes = 0L
            var retainedBytes = 0L
            var entryCount = 0
            fun isNotice(name: String): Boolean {
                val plain = name.substringAfterLast('/')
                return Regex("(?i)(LICENSE|LICENCE|NOTICE)(?:[._-].*)?").matches(plain)
            }
            fun readEntry(input: InputStream, retain: Boolean): ByteArray {
                val output = if (retain) ByteArrayOutputStream() else null
                val buffer = ByteArray(8192)
                var entrySize = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    expandedBytes += count
                    require(expandedBytes <= maxExpandedBytes) { "通知抽出の総展開量が上限を超えました。" }
                    if (retain) {
                        entrySize += count
                        retainedBytes += count
                        require(entrySize <= MAX_TEXT && retainedBytes <= maxRetainedBytes) { "通知本文の保持量が上限を超えました。" }
                        output!!.write(buffer, 0, count)
                    }
                }
                return output?.toByteArray() ?: byteArrayOf()
            }
            fun addNotice(path: String, bytes: ByteArray) {
                require(result.size < 1000) { "通知本文の件数が上限を超えました。" }
                result.add(path to bytes)
            }
            ZipFile(file).use { zip ->
                val entries = zip.entries().asSequence().take(maxEntries + 1).toList()
                require(entries.size <= maxEntries) { "通知抽出対象のentryが多すぎます。" }
                entryCount = entries.size
                require(entries.all { it.name.length <= 2048 }) { "通知抽出対象の名前が長すぎます。" }
                entries.filter { !it.isDirectory }.sortedBy { it.name }.forEach { entry ->
                    if (isNotice(entry.name)) zip.getInputStream(entry).use { addNotice(entry.name, readEntry(it, true)) }
                    if (file.extension.equals("aar", true) && (entry.name == "classes.jar" || entry.name.matches(Regex("libs/[^/]+\\.jar")))) {
                        ZipInputStream(zip.getInputStream(entry)).use { nested ->
                            while (true) {
                                val child = nested.nextEntry ?: break
                                require(++entryCount <= maxEntries && child.name.length <= 2048) { "入れ子JARのentryが上限を超えました。" }
                                val retain = !child.isDirectory && isNotice(child.name)
                                // 非通知entryも上限付きで明示的に読み切り、closeEntryによる無制限展開を避ける。
                                val bytes = readEntry(nested, retain)
                                if (retain) addNotice("${entry.name}!/${child.name}", bytes)
                                nested.closeEntry()
                            }
                        }
                    }
                }
            }
            return result
        }
    }
}
