plugins {
    `java-gradle-plugin`
}

dependencies {
    // deps.LoaderApi (core) parses loader api poms/meta
    "coreImplementation"(libs.kotlinx.serialization.json)
    // NoGroovyTwin parity-guard opt-out marker
    "coreImplementation"(project(":unimined-api"))
    api(project(":unimined-minecraft"))
    api(project(":unimined-mapping"))
    api(project(":unimined-remap"))
    api(project(":unimined-transform"))
    api(project(":unimined-runs"))
    api(project(":unimined-ide"))
    api(project(":unimined-source"))
    api(project(":unimined-migrate"))
    api(project(":unimined-minecraft:provider-native"))
    api(project(":unimined-minecraft:provider-conversion"))
    api(project(":unimined-remap:remapper-tiny"))
    api(project(":unimined-remap:remapper-uml"))
    api(project(":unimined-remap:remapper-access"))
    api(project(":unimined-source:decompiler-vineflower"))
    api(project(":unimined-transform:transform-fabric"))
    api(project(":unimined-transform:transform-forge"))
    api(project(":unimined-transform:transform-jarmod"))
    api(project(":unimined-transform:transform-bukkit"))
    api(project(":unimined-transform:transform-misc"))
    api(project(":unimined-transform:transform-conversion"))
    api(project(":unimined-mod"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    // GroovyTwinParity reflective guard — scans the whole bundled stack
    "testGradleImplementation"(testFixtures(project(":unimined-api")))
    "testGradleImplementation"(gradleTestKit())
    "testGradleImplementation"(libs.junit.jupiter)
    "testGradleRuntimeOnly"(libs.junit.platform.launcher)
}

gradlePlugin {
    plugins {
        create("unimined") {
            id = "xyz.wagyourtail.unimined"
            implementationClass = "xyz.wagyourtail.unimined.GradlePlugin"
        }
    }
}
