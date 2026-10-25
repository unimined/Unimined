plugins {
    `java-gradle-plugin`
}

dependencies {
    api(project(":unimined-transform"))
    api(project(":unimined-remap"))
}

gradlePlugin {
    plugins {
        create("transform-misc") {
            id = "xyz.wagyourtail.unimined.transform.misc"
            implementationClass = "xyz.wagyourtail.unimined.transform.misc.GradlePlugin"
        }
    }
}
