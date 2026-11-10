plugins {
    `java-gradle-plugin`
}

dependencies {
    "coreApi"(project(":unimined-api"))
    "coreApi"(project(":unimined-minecraft"))
    api(project(":unimined-minecraft"))
    implementation(project(":unimined-api"))
    implementation(project(":unimined-remap"))
    implementation(project(":unimined-transform"))
    implementation(project(":unimined-runs"))
    // ExpectPlatform is optional — adapter activates via withPlugin
    // only when its plugin is applied; only the extension's
    // agentJar/TransformPlatform surface is consumed. 1.2.0 is the
    // floor — earlier agents lack the -Dexpect.side strip support.
    compileOnly("xyz.wagyourtail.unimined.expect-platform:expect-platform:1.2.0")

    "testGradleImplementation"(gradleTestKit())
    "testGradleImplementation"(libs.junit.jupiter)
    "testGradleImplementation"("xyz.wagyourtail.unimined.expect-platform:expect-platform:1.2.0")
    "testGradleRuntimeOnly"(libs.junit.platform.launcher)
}

gradlePlugin {
    plugins {
        create("adapterExpectPlatform") {
            id = "xyz.wagyourtail.unimined.adapter-expectplatform"
            implementationClass = "xyz.wagyourtail.unimined.adapter.expectplatform.GradlePlugin"
        }
    }
}
