plugins {
    `java-gradle-plugin`
}

dependencies {
    "coreApi"(project(":unimined-transform"))
    "coreApi"(project(":unimined-minecraft"))
    "coreApi"(libs.asm)
    api(project(":unimined-transform"))
    api(project(":unimined-remap"))
    api(project(":unimined-mod"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(testFixtures(project(":unimined-remap")))
    testImplementation(project(":unimined-remap:remapper-uml"))
}

gradlePlugin {
    plugins {
        create("transform-jarmod") {
            id = "xyz.wagyourtail.unimined.transform.jarmod"
            implementationClass = "xyz.wagyourtail.unimined.transform.jarmod.GradlePlugin"
        }
    }
}
