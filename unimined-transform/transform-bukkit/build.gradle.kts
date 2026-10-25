plugins {
    `java-gradle-plugin`
}

dependencies {
    api(project(":unimined-transform"))
    api(project(":unimined-remap"))
}

gradlePlugin {
    plugins {
        create("transform-bukkit") {
            id = "xyz.wagyourtail.unimined.transform.bukkit"
            implementationClass = "xyz.wagyourtail.unimined.transform.bukkit.GradlePlugin"
        }
    }
}
