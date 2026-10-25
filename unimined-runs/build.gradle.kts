plugins {
    `java-gradle-plugin`
}

dependencies {
    api(project(":unimined-transform"))
}

gradlePlugin {
    plugins {
        create("runs") {
            id = "xyz.wagyourtail.unimined.runs"
            implementationClass = "xyz.wagyourtail.unimined.runs.GradlePlugin"
        }
    }
}
