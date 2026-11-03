plugins {
    `java-gradle-plugin`
}

dependencies {
    api(project(":unimined-minecraft"))
    api(project(":unimined-minecraft:provider-native"))
    "coreApi"(project(":unimined-minecraft"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

gradlePlugin {
    plugins {
        create("provider-buildtools") {
            id = "xyz.wagyourtail.unimined.provider.buildtools"
            implementationClass = "xyz.wagyourtail.unimined.provider.buildtools.GradlePlugin"
        }
    }
}
