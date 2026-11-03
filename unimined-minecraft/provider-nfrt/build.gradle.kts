plugins {
    `java-gradle-plugin`
}

dependencies {
    api(project(":unimined-minecraft"))
}

gradlePlugin {
    plugins {
        create("provider-nfrt") {
            id = "xyz.wagyourtail.unimined.provider.nfrt"
            implementationClass = "xyz.wagyourtail.unimined.provider.nfrt.GradlePlugin"
        }
    }
}
