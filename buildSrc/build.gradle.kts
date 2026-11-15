plugins {
    `kotlin-dsl`
}

if (File("/build-cache").exists()) {
    layout.buildDirectory.set(File("/build-cache/buildSrc"))
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
