plugins {
    `java-gradle-plugin`
}

dependencies {
    api(project(":unimined-publish"))
    // typed calls against ModPublisherGradleExtension — optional
    // integration: classes exist at runtime only when the consuming
    // build applies com.hypherionmc.modutils.modpublisher
    compileOnly("com.hypherionmc.modutils:modpublisher:2.2.4")

    testImplementation(project(":unimined-mod-loader-merge"))
    // real ModPublisher for the dry-run e2e — uploads never happen
    // (debug.set(true) short-circuits them)
    testImplementation("com.hypherionmc.modutils:modpublisher:2.2.4")
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
