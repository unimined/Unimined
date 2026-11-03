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
    "coreApi"(project(":unimined-minecraft:provider-native"))
    "coreApi"(libs.kotlinx.serialization.json)
    api(project(":unimined-minecraft"))
    api(project(":unimined-minecraft:provider-native"))
}

gradlePlugin {
    plugins {
        create("provider-conversion") {
            id = "xyz.wagyourtail.unimined.provider.conversion"
            implementationClass = "xyz.wagyourtail.unimined.provider.conversion.GradlePlugin"
        }
    }
}
