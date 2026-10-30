plugins {
    `java-gradle-plugin`
}

dependencies {
    "coreApi"(project(":unimined-transform"))
    "coreApi"(project(":unimined-api"))
    api(project(":unimined-transform"))
    api(project(":unimined-remap"))
    api(project(":unimined-minecraft"))
}

gradlePlugin {
    plugins {
        create("transform-bukkit") {
            id = "xyz.wagyourtail.unimined.transform.bukkit"
            implementationClass = "xyz.wagyourtail.unimined.transform.bukkit.GradlePlugin"
        }
    }
}
