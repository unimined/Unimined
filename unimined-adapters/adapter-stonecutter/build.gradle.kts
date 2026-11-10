plugins {
    `java-gradle-plugin`
}

dependencies {
    "coreApi"(project(":unimined-api"))
    "coreApi"(project(":unimined-minecraft"))
    api(project(":unimined-minecraft"))
    implementation(project(":unimined-api"))
    // Stonecutter is optional at apply-time (withPlugin-guarded) but a
    // real compile dep — it publishes only a Java-21
    // shadowRuntimeElements variant, which is why this module targets
    // 21 (stonecutter's own floor anyway — the adapter is dead weight
    // without it). Runtime usage: no apiElements variant exists.
    compileOnly("dev.kikugie:stonecutter:0.9.8") {
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE,
                objects.named(Usage.JAVA_RUNTIME))
        }
    }

    "testGradleImplementation"(gradleTestKit())
    "testGradleImplementation"(libs.junit.jupiter)
    "testGradleImplementation"("dev.kikugie:stonecutter:0.9.8") {
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE,
                objects.named(Usage.JAVA_RUNTIME))
        }
    }
    "testGradleRuntimeOnly"(libs.junit.platform.launcher)
}

// stonecutter's classes are v65 — this module alone targets 21 while
// the rest of the build stays at 17
kotlin { jvmToolchain(21) }

gradlePlugin {
    plugins {
        create("adapterStonecutter") {
            id = "xyz.wagyourtail.unimined.adapter-stonecutter"
            implementationClass = "xyz.wagyourtail.unimined.adapter.stonecutter.GradlePlugin"
        }
    }
}
