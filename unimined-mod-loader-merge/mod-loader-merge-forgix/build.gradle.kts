plugins {
    kotlin("jvm")
}

dependencies {
    "coreApi"(project(":unimined-mod-loader-merge"))
    implementation(libs.forgix)
}
