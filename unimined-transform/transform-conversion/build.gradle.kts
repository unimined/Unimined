plugins {
    `java-gradle-plugin`
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    "coreApi"(project(":unimined-transform"))
    "coreApi"(project(":unimined-minecraft"))
    "coreApi"(libs.kotlinx.serialization.json)
    api(project(":unimined-transform"))
    // env wiring probes the product table (extraLibraries gate for the
    // bundled-library strip) — same direction as
    // transform-bukkit -> provider-buildtools
    implementation(project(":unimined-minecraft:provider-conversion"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

gradlePlugin {
    plugins {
        create("transform-conversion") {
            id = "xyz.wagyourtail.unimined.transform.conversion"
            implementationClass = "xyz.wagyourtail.unimined.transform.conversion.GradlePlugin"
        }
    }
}
