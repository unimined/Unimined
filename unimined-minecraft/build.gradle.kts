plugins {
    `java-gradle-plugin`
}

dependencies {
    api(project(":unimined-api"))
    api(project(":unimined-mapping"))

    testImplementation(libs.junit.jupiter)
    testImplementation(project(":unimined-mapping:mapping-uml"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

gradlePlugin {
    plugins {
        create("minecraft") {
            id = "xyz.wagyourtail.unimined.minecraft"
            implementationClass = "xyz.wagyourtail.unimined.minecraft.GradlePlugin"
        }
    }
}
