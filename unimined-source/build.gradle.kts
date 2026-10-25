plugins {
    `java-gradle-plugin`
}

dependencies {
    api(project(":unimined-transform"))
}

gradlePlugin {
    plugins {
        create("source") {
            id = "xyz.wagyourtail.unimined.source"
            implementationClass = "xyz.wagyourtail.unimined.source.GradlePlugin"
        }
    }
}
