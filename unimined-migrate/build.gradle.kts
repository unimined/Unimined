plugins {
    `java-gradle-plugin`
}

dependencies {
    api(project(":unimined-minecraft"))
    api(project(":unimined-mapping"))
    implementation(libs.unimined.mapping.library)
    implementation(libs.unimined.mapping.remapper)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

gradlePlugin {
    plugins {
        create("migrate") {
            id = "xyz.wagyourtail.unimined.migrate"
            implementationClass = "xyz.wagyourtail.unimined.migrate.GradlePlugin"
        }
    }
}
