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
        create("publishModpublisher") {
            id = "xyz.wagyourtail.unimined.publish-modpublisher"
            implementationClass = "xyz.wagyourtail.unimined.publish.modpublisher.GradlePlugin"
        }
    }
}
