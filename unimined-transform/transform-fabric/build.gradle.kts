plugins {
    `java-gradle-plugin`
}

dependencies {
    api(project(":unimined-transform"))
    api(project(":unimined-remap"))
}

gradlePlugin {
    plugins {
        create("transform-fabric") {
            id = "xyz.wagyourtail.unimined.transform.fabric"
            implementationClass = "xyz.wagyourtail.unimined.transform.fabric.GradlePlugin"
        }
    }
}
