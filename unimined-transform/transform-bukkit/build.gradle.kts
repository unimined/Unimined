plugins {
    `java-gradle-plugin`
}

dependencies {
    "coreApi"(project(":unimined-transform"))
    "coreApi"(project(":unimined-api"))
    "coreApi"(project(":unimined-minecraft"))
    api(project(":unimined-transform"))
    api(project(":unimined-minecraft:provider-buildtools"))
    api(project(":unimined-remap"))
    api(project(":unimined-minecraft"))
    "coreApi"(project(":unimined-mod"))
    "coreApi"(libs.snakeyaml)
    "coreImplementation"(libs.jbsdiff)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.jbsdiff)
}

gradlePlugin {
    plugins {
        create("transform-bukkit") {
            id = "xyz.wagyourtail.unimined.transform.bukkit"
            implementationClass = "xyz.wagyourtail.unimined.transform.bukkit.GradlePlugin"
        }
    }
}
