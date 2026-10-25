plugins {
    `java-gradle-plugin`
}

dependencies {
    api(project(":unimined-transform"))
    api(project(":unimined-remap"))
}

gradlePlugin {
    plugins {
        create("transform-forge") {
            id = "xyz.wagyourtail.unimined.transform.forge"
            implementationClass = "xyz.wagyourtail.unimined.transform.forge.GradlePlugin"
        }
    }
}
