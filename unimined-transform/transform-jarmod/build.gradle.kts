plugins {
    `java-gradle-plugin`
}

dependencies {
    api(project(":unimined-transform"))
    api(project(":unimined-remap"))
    api(project(":unimined-mod"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

gradlePlugin {
    plugins {
        create("transform-jarmod") {
            id = "xyz.wagyourtail.unimined.transform.jarmod"
            implementationClass = "xyz.wagyourtail.unimined.transform.jarmod.GradlePlugin"
        }
    }
}
