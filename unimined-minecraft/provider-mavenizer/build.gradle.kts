plugins {
    `java-gradle-plugin`
}

dependencies {
    api(project(":unimined-minecraft"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

gradlePlugin {
    plugins {
        create("provider-mavenizer") {
            id = "xyz.wagyourtail.unimined.provider.mavenizer"
            implementationClass = "xyz.wagyourtail.unimined.provider.mavenizer.GradlePlugin"
        }
    }
}
