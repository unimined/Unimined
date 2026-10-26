plugins {
    `java-gradle-plugin`
}

dependencies {
    api(project(":unimined-minecraft"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(project(":unimined-transform"))
    testImplementation(project(":unimined-transform:transform-fabric"))
}

gradlePlugin {
    plugins {
        create("mod") {
            id = "xyz.wagyourtail.unimined.mod"
            implementationClass = "xyz.wagyourtail.unimined.mod.GradlePlugin"
        }
    }
}
