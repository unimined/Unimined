plugins {
    `java-gradle-plugin`
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

dependencies {
    "coreApi"(project(":unimined-minecraft"))
    "coreApi"(project(":unimined-api"))
    "coreApi"(libs.kotlinx.serialization.json)
    "coreApi"(libs.asm)
    api(project(":unimined-minecraft"))
    api(project(":unimined-remap"))
}

gradlePlugin {
    plugins {
        create("provider-native") {
            id = "xyz.wagyourtail.unimined.provider.native"
            implementationClass = "xyz.wagyourtail.unimined.provider.native.GradlePlugin"
        }
    }
}
