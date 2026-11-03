plugins {
    `java-gradle-plugin`
}

dependencies {
    api(project(":unimined-publish"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

gradlePlugin {
    plugins {
        create("publishMaven") {
            id = "xyz.wagyourtail.unimined.publish-maven"
            implementationClass = "xyz.wagyourtail.unimined.publish.maven.GradlePlugin"
        }
    }
}
