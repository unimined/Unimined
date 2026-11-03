plugins {
    `java-gradle-plugin`
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
        create("provider-native") {
            id = "xyz.wagyourtail.unimined.provider.native"
            implementationClass = "xyz.wagyourtail.unimined.provider.native.GradlePlugin"
        }
    }
}
