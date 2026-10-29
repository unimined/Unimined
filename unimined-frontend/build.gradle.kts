plugins {
    `java-gradle-plugin`
}

dependencies {
    api(project(":unimined-minecraft"))
    api(project(":unimined-mapping"))
    api(project(":unimined-remap"))
    api(project(":unimined-transform"))
    api(project(":unimined-runs"))
    api(project(":unimined-runs-ide"))
    api(project(":unimined-source"))
    api(project(":unimined-migrate"))
    api(project(":unimined-minecraft:provider-native"))
    api(project(":unimined-remap:remapper-tiny"))
    api(project(":unimined-remap:remapper-uml"))
    api(project(":unimined-remap:remapper-access"))
    api(project(":unimined-source:decompiler-vineflower"))
    api(project(":unimined-transform:transform-fabric"))
    api(project(":unimined-transform:transform-forge"))
    api(project(":unimined-transform:transform-jarmod"))
    api(project(":unimined-transform:transform-bukkit"))
    api(project(":unimined-transform:transform-misc"))
    api(project(":unimined-mod"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

gradlePlugin {
    plugins {
        create("unimined") {
            id = "xyz.wagyourtail.unimined"
            implementationClass = "xyz.wagyourtail.unimined.GradlePlugin"
        }
    }
}
