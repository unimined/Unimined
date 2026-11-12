plugins {
    java
    id("xyz.wagyourtail.unimined")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
    maven("https://api.modrinth.com/maven")
}

unimined {
    minecraft {
        version = "1.20.1"

        loader("fabric", "0.16.0")
        mappings {
            yarn(10)
        }
    }
}

dependencies {
    // a realistic mod set — exercises per-artifact remap transforms
    // (mixed metadata: AW rewrites, nested jars, refmaps, non-mod libs)
    modImplementation("net.fabricmc.fabric-api:fabric-api:0.92.2+1.20.1")
    modImplementation("net.fabricmc:fabric-language-kotlin:1.11.0+kotlin.2.0.0")
    modImplementation("maven.modrinth:modmenu:7.2.2")
    modImplementation("maven.modrinth:spark:1.10.187-fabric")
    modImplementation("maven.modrinth:lazydfu:0.1.3")
    modImplementation("maven.modrinth:immediatelyfast:3EgIBnht")
    modImplementation("maven.modrinth:jsmacros:fw5Ush4t")
}

// perf-bench driver task: the mod-remap wave fires on classpath
// materialization, not on `classes` — javac only loads types whose
// members it accesses, so an empty source compiles with the unimined
// views never resolved (silently missing classpath, esp. under the
// configuration cache). The mod/MC artifacts ride the sourceSet's
// FileCollection classpaths (NOT the `compileClasspath` Configuration,
// which only ever holds declared deps) — resolving these collections
// here makes the benchmark exercise the real transform work.
tasks.register("resolveDevClasspath") {
    // file-collection inputs are CC-safe and materialize during input
    // fingerprinting — that's the point: the mod-remap wave runs here
    val compileCp = sourceSets.named("main").map { it.compileClasspath }
    val runtimeCp = sourceSets.named("main").map { it.runtimeClasspath }
    inputs.files(compileCp)
    inputs.files(runtimeCp)
    doLast {
        println("compileClasspath: ${compileCp.get().files.size} files")
        println("runtimeClasspath: ${runtimeCp.get().files.size} files")
    }
}
