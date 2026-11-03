plugins {
    `java-gradle-plugin`
}

dependencies {
    "coreApi"(project(":unimined-transform"))
    "coreApi"(project(":unimined-transform:transform-jarmod"))
    "coreApi"(project(":unimined-api"))
    "coreApi"(libs.unimined.mapping.library)
    "coreApi"(libs.kotlinx.serialization.json)
    api(project(":unimined-transform"))
    api(project(":unimined-remap"))
    api(project(":unimined-minecraft"))
    implementation(project(":unimined-remap:remapper-access"))
    implementation(project(":unimined-remap:remapper-jst"))
    // GDiffPatcher for binpatch application; pack200 unpack +
    // lzma decode are commons-compress (+xz)
    "coreImplementation"(libs.javaxdelta)
    "coreImplementation"(libs.commons.compress)
    implementation(libs.javaxdelta)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.asm)
    implementation(libs.asm.tree)
    implementation(libs.classtransform)
    implementation(libs.classtransform.acp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

gradlePlugin {
    plugins {
        create("transform-forge") {
            id = "xyz.wagyourtail.unimined.transform.forge"
            implementationClass = "xyz.wagyourtail.unimined.transform.forge.GradlePlugin"
        }
    }
}

// fg2 Java lives in `src/fg2/java` and compiles at release 8 in a
// dedicated task: `fg2.rt` classes execute inside the launched
// (Java 8) JVM, and `fg2.*Tx` handler bodies get @CInline'd into
// Java-8 target classes — release 8 keeps inlined bytecode
// Java-8-safe (e.g. StringBuilder concat instead of
// StringConcatFactory invokedynamic, which 1.8 can't resolve). A
// separate task keeps `compileJava`'s target attribute (and thus
// module variant resolution) untouched; a custom sourceSet would hit
// the same Java-8-attribute dependency resolution wall.
val fg2Dir = "src/fg2/java"
val compileFg2 = tasks.register<JavaCompile>("compileFg2") {
    source = fileTree(fg2Dir)
    classpath = sourceSets.main.get().compileClasspath
    destinationDirectory.set(layout.buildDirectory.dir("classes/fg2"))
    options.release.set(8)
}
tasks.named<JavaCompile>("compileJava") {
    // fg2 is declared as a main srcDir for IDE source-root modeling
    // only — release-8 bytecode comes from compileFg2, never here
    exclude("**/fg2/**")
}
// Kotlin sources reference fg2 classes (e.g. `FG2DevFixes` →
// `FolderResourcePackFix`) — resolved as binaries since the sources
// sit outside the main source set
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") {
    dependsOn(compileFg2)
    libraries.from(compileFg2.map { it.destinationDirectory })
}
// the release-8 classes double as the resource `getResourceAsStream`
// resolution for `FG2DevFixes.spliceHelpers`
sourceSets.named("main") {
    java.srcDir(fg2Dir)
    output.dir(
        mapOf("builtBy" to compileFg2),
        layout.buildDirectory.dir("classes/fg2")
    )
}
