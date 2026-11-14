plugins {
    `java-gradle-plugin`
}

dependencies {
    "coreApi"(project(":unimined-transform"))
    "coreApi"(project(":unimined-minecraft"))
    "coreApi"(libs.asm)
    api(project(":unimined-transform"))
    api(project(":unimined-remap"))
    // access-doc apply step for user-declared AT/AW/CT files
    implementation(project(":unimined-remap:remapper-access"))
    "coreApi"(project(":unimined-mod"))
    api(project(":unimined-mod"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(testFixtures(project(":unimined-remap")))
    testImplementation(project(":unimined-remap:remapper-uml"))

    // env.version resolution goes through the ProductProvider SPI —
    // wiring tests need a provider for loader-less `minecraft:native`
    // specs to get past selection (ServiceLoader-registered).
    "testGradleImplementation"(project(":unimined-minecraft:provider-native"))
}

gradlePlugin {
    plugins {
        create("transform-jarmod") {
            id = "xyz.wagyourtail.unimined.transform.jarmod"
            implementationClass = "xyz.wagyourtail.unimined.transform.jarmod.GradlePlugin"
        }
    }
}

// `internal/**/rt/` helpers are spliced into era jars that launch on
// era JVMs (fg1-era runs Java 8 — v52 floor) — recompile them at
// release 8 over the v61 classes the normal compile emitted, inside
// the same task so downstream consumers see no extra producer
tasks.named<JavaCompile>("compileCoreJava") {
    val rtSrc = fileTree("src/core/java") { include("**/rt/**/*.java") }
    val rtOut = layout.buildDirectory.dir("classes/java/core")
    val rtCp = objects.fileCollection().from(
        sourceSets["core"].compileClasspath, rtOut)
    inputs.files(rtSrc)
    inputs.files(rtCp)
    doLast {
        if (rtSrc.isEmpty) return@doLast
        val out = rtOut.get().asFile
        val javac = javax.tools.ToolProvider.getSystemJavaCompiler()
        val rc = javac.run(null, null, null,
            "-nowarn", "-cp", rtCp.asPath, "-d", out.absolutePath,
            "--release", "8",
            *rtSrc.files.map { it.absolutePath }.toTypedArray())
        if (rc != 0) throw GradleException("core rt/ recompile failed ($rc)")
    }
}
