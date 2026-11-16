plugins {
    `java-gradle-plugin`
}

dependencies {
    "coreApi"(project(":unimined-api"))
    "coreApi"(libs.unimined.mapping.library)
    "coreApi"(libs.commons.kt)
    "coreApi"(libs.commons.core)
    api(project(":unimined-api"))
    // the default composer merges typed views through UML's tree/sink model
    api(libs.unimined.mapping.library)
    // hierarchy scan + member-group fill for the bytecode-fill
    // post-processor (ASMInheritanceTree/LazyASMInheritanceTree)
    "coreImplementation"(libs.unimined.mapping.remapper)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.asm)
    testRuntimeOnly(libs.junit.platform.launcher)
}

gradlePlugin {
    plugins {
        create("mapping") {
            id = "xyz.wagyourtail.unimined.mapping"
            implementationClass = "xyz.wagyourtail.unimined.mapping.GradlePlugin"
        }
        // was the mapping-uml subproject — folded in now that UML is an
        // always-present api dep; the plugin id stays for compat
        create("mapping-uml") {
            id = "xyz.wagyourtail.unimined.mapping.uml"
            implementationClass = "xyz.wagyourtail.unimined.mapping.uml.GradlePlugin"
        }
    }
}
