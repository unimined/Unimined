plugins {
    `java-gradle-plugin`
}

dependencies {
    "coreApi"(project(":unimined-api"))
    "coreApi"(project(":unimined-minecraft"))
    api(project(":unimined-minecraft"))
    implementation(project(":unimined-transform"))
    implementation(project(":unimined-api"))
    // Standalone PatchBase — the main artifact bundles the installer
    // sourceset output (PatchbaseInstaller + ClassDiff .bdiff) AND the
    // generic create/apply patch task types. Classifier artifacts share
    // the main pom, which can't carry installerImplementation deps, so
    // asm/javaxdelta are redeclared here.
    implementation("xyz.wagyourtail.unimined:patchbase:2.0.0")
    implementation("org.ow2.asm:asm:9.7.1")
    implementation("com.nothome:javaxdelta:2.0.1")
    implementation("io.github.java-diff-utils:java-diff-utils:4.12")

    testImplementation("org.ow2.asm:asm-tree:9.7.1")

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    "testGradleImplementation"(gradleTestKit())
    "testGradleImplementation"(libs.junit.jupiter)
    "testGradleRuntimeOnly"(libs.junit.platform.launcher)
}

gradlePlugin {
    plugins {
        create("adapterPatchbase") {
            id = "xyz.wagyourtail.unimined.adapter-patchbase"
            implementationClass = "xyz.wagyourtail.unimined.adapter.patchbase.GradlePlugin"
        }
    }
}
