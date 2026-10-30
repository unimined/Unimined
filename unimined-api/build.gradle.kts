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
