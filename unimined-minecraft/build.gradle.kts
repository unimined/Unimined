plugins {
    `java-gradle-plugin`
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    "coreApi"(project(":unimined-api"))
    "coreApi"(libs.kotlinx.serialization.json)
    "coreApi"(libs.asm)
    "coreApi"(libs.asm.tree)
    "coreApi"(libs.asm.analysis)
    "coreImplementation"(libs.commons.kt)
    api(project(":unimined-api"))
    api(project(":unimined-mapping"))

    // KotlinCompile wiring (jvmTarget) — user-applied KGP is runtime;
    // compile against the stable api jar only
    compileOnly(libs.kotlin.gradle.plugin.api)

    testImplementation(libs.junit.jupiter)
    testImplementation(project(":unimined-mapping"))
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.commons.kt)
    testRuntimeOnly(libs.junit.platform.launcher)
}

gradlePlugin {
    plugins {
        create("minecraft") {
            id = "xyz.wagyourtail.unimined.minecraft"
            implementationClass = "xyz.wagyourtail.unimined.minecraft.GradlePlugin"
        }
    }
}
