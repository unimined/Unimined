plugins {
    `java-gradle-plugin`
}

dependencies {
    "coreApi"(project(":unimined-api"))
    api(project(":unimined-api"))
    api(project(":unimined-mapping"))

    // KotlinCompile wiring (jvmTarget) — user-applied KGP is runtime;
    // compile against the stable api jar only
    compileOnly(libs.kotlin.gradle.plugin.api)

    testImplementation(libs.junit.jupiter)
    testImplementation(project(":unimined-mapping"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

gradlePlugin {
    plugins {
        create("minecraft") {
            id = "xyz.wagyourtail.unimined.minecraft"
            implementationClass = "xyz.wagyourtail.unimined.minecraft.GradlePlugin"
        }
    }
}
