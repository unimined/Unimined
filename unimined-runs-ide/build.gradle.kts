plugins {
    `java-gradle-plugin`
}

dependencies {
    api(project(":unimined-runs"))
}

gradlePlugin {
    plugins {
        create("runs-ide") {
            id = "xyz.wagyourtail.unimined.runs.ide"
            implementationClass = "xyz.wagyourtail.unimined.runs.ide.GradlePlugin"
        }
    }
}
