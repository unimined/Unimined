plugins {
    `java-gradle-plugin`
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":unimined-transform"))
    api(project(":unimined-remap"))
    api(project(":unimined-mod"))
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
