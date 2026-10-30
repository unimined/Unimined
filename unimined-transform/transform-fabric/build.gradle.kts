plugins {
    `java-gradle-plugin`
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    "coreApi"(project(":unimined-transform"))
    "coreApi"(project(":unimined-api"))
    "coreApi"(project(":unimined-mod"))
    "coreApi"(libs.unimined.mapping.library)
    "coreApi"(libs.kotlinx.serialization.json)
    api(project(":unimined-transform"))
    api(project(":unimined-remap"))
    api(project(":unimined-mod"))
    implementation(project(":unimined-remap:remapper-access"))
    implementation(libs.asm)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

gradlePlugin {
    plugins {
        create("transform-fabric") {
            id = "xyz.wagyourtail.unimined.transform.fabric"
            implementationClass = "xyz.wagyourtail.unimined.transform.fabric.GradlePlugin"
        }
    }
}
