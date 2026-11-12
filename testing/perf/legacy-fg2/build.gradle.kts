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
    maven("https://maven.blamejared.com")
}

unimined {
    minecraft {
        version = "1.12.2"

        loader("forge", "14.23.5.2860")
        mappings {
            searge()
            mcp("stable", "39-1.12")
        }
    }
}

dependencies {
    modImplementation("mezz.jei:jei_1.12.2:4.16.1.302")
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
