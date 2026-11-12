plugins {
    java
    id("xyz.wagyourtail.unimined")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

repositories {
    mavenCentral()
    maven("https://maven.minecraftforge.net/")
}

unimined {
    minecraft {
        version = "1.20.1"

        loader("forge", "47.2.20")
        mappings {
            mojmap()
        }
    }
}

// perf-bench/perf-verify driver: materializing the sourceSet classpath
// FileCollections is what fires the remap transform wave (see
// fabric-modern for the full rationale).
tasks.register("resolveDevClasspath") {
    val compileCp = sourceSets.named("main").map { it.compileClasspath }
    val runtimeCp = sourceSets.named("main").map { it.runtimeClasspath }
    inputs.files(compileCp)
    inputs.files(runtimeCp)
    doLast {
        println("compileClasspath: ${compileCp.get().files.size} files")
        println("runtimeClasspath: ${runtimeCp.get().files.size} files")
    }
}
