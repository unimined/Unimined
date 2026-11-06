plugins {
    kotlin("jvm")
}

dependencies {
    "coreApi"(project(":unimined-mod-loader-merge"))
    implementation(libs.forgix)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
