plugins {
    `java-gradle-plugin`
}

dependencies {
    api(project(":unimined-minecraft"))
    api(project(":unimined-minecraft:provider-native"))
    "coreApi"(project(":unimined-minecraft"))
    "coreApi"(project(":unimined-minecraft:provider-native"))
}

gradlePlugin {
    plugins {
        create("provider-buildtools") {
            id = "xyz.wagyourtail.unimined.provider.buildtools"
            implementationClass = "xyz.wagyourtail.unimined.provider.buildtools.GradlePlugin"
        }
    }
}
