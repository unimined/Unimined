plugins {
    `java-gradle-plugin`
}

dependencies {
    api(project(":unimined-transform"))
    api(project(":unimined-remap"))
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
