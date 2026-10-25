plugins {
    `java-gradle-plugin`
}

dependencies {
    api(project(":unimined-minecraft"))
}

gradlePlugin {
    plugins {
        create("provider-mavenizer") {
            id = "xyz.wagyourtail.unimined.provider.mavenizer"
            implementationClass = "xyz.wagyourtail.unimined.provider.mavenizer.GradlePlugin"
        }
    }
}
