plugins {
    `java-gradle-plugin`
}

dependencies {
    api(project(":unimined-source"))
}

gradlePlugin {
    plugins {
        create("decompiler-vineflower") {
            id = "xyz.wagyourtail.unimined.decompiler.vineflower"
            implementationClass = "xyz.wagyourtail.unimined.decompiler.vineflower.GradlePlugin"
        }
    }
}
