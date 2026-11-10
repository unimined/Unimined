plugins {
    `java-library`
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    compileOnly(gradleApi())

    "coreApi"(libs.commons.kt)
    "coreApi"(libs.commons.core)
    "coreApi"(libs.kotlinx.serialization.json)
    "coreApi"(libs.unimined.mapping.library)
    api(libs.commons.kt)
    api(libs.commons.gradle)
    api(libs.kotlinx.serialization.json)
    // UML is the canonical mapping library — the api exposes its typed view
    api(libs.unimined.mapping.library)

    testImplementation(gradleApi())
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.slf4j.nop)
}

// ProjectBuilder (testGradle) needs java.lang opens for Gradle's
// classloader injection — java-gradle-plugin sets this on plugin
// modules automatically; this module is plain java-library
tasks.named<Test>("testGradle") {
    jvmArgs("--add-opens", "java.base/java.lang=ALL-UNNAMED")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
