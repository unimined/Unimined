import xyz.wagyourtail.unimined.api.spec.Side

plugins {
    java
    id("xyz.wagyourtail.unimined")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

repositories {
    // ornithemc artifacts, mirrored — the canonical repo is kept second
    // for environments where maven.ornithemc.net is reachable
    maven("https://maven.wagyourtail.xyz/releases/")
    maven("https://maven.ornithemc.net/releases")
    mavenCentral()
}

unimined {
    minecraft {
        version = "b1.7.3"
        side = Side.MERGED

        loader("ornithe", "0.19.5")
        mappings {
            ornitheGenVersion = 2
            feather(2, 2)
            nests(1)
            sparrow(2)
        }
    }

    mod {
        id = "examplemod"
        name = "examplemod"
        version = "1.0.0"
        loader("ornithe") { }
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
