plugins {
    `kotlin-dsl`
}

if (File("/build-cache").exists()) {
    buildDir = File("/build-cache/buildSrc")
}

repositories {
    // The org.jetbrains.kotlin.jvm plugin requires a repository
    // where to download the Kotlin compiler dependencies from.
    mavenCentral()
}
