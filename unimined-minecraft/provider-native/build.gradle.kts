plugins {
    `java-gradle-plugin`
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

dependencies {
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
