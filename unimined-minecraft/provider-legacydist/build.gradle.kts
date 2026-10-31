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
    api(project(":unimined-minecraft"))
}

gradlePlugin {
    plugins {
        create("provider-legacydist") {
            id = "xyz.wagyourtail.unimined.provider.legacydist"
            implementationClass = "xyz.wagyourtail.unimined.provider.legacydist.GradlePlugin"
        }
    }
}
