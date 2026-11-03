plugins {
    `java-gradle-plugin`
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":unimined-runs"))
    "coreApi"(libs.kotlinx.serialization.json)
    "coreApi"(libs.xmlutil.serialization)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

gradlePlugin {
    plugins {
        create("ide") {
            id = "xyz.wagyourtail.unimined.ide"
            implementationClass = "xyz.wagyourtail.unimined.ide.GradlePlugin"
        }
    }
}
