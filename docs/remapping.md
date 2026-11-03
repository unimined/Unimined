# Remapping

## Remapping Mods

Unimined provides the ability to remap mods you depend on to the mappings you are using.
by default, unimined only implements `modImplementation`, but the others are easily creatable by the user.

For Example:

=== "Groovy"

    ```groovy
    configurations {
        modCompileOnly
        compileOnly.extendsFrom modCompileOnly
    }

    unimined.minecraft {
        ...
        mods {
            remap(configurations.modCompileOnly) {
            }

            // this is basically just a shortcut for `remap(configurations.modImplementation)`
            modImplementation {
                // you can do this is mods have the wrong access widener mapping, but it may break runs
                catchAWNamespaceAssertion()
            }
        }
    }

    dependencies {
        modCompileOnly "mod.group:mod.artifact:mod.version"
    }
    ```

=== "Kotlin"

    ```kotlin
    val modCompileOnly by configurations.creating

    configurations {
        compileOnly {
            extendsFrom(modCompileOnly)
        }
    }

    unimined.minecraft {
        ...
        mods {
            remap(modCompileOnly) {

            }

            // this is basically just a shortcut for `remap(configurations.modImplementation)`
            modImplementation {
                // you can do this is mods have the wrong access widener mapping, but it may break runs
                catchAWNamespaceAssertion()
            }
        }
    }

    dependencies {
        modCompileOnly("mod.group:mod.artifact:mod.version")
    }
    ```


## Remapping Output

unimined provides a default `remapJar` task for each configuration, it may be useful to create an extra or custom remap
task


=== "Groovy"

    ```groovy

    unimined.minecraft {
        ...
        defaultRemapJar = false // disable the default remapJar task

        remap(myJarTask) {
            prodNamespace "intermediary" // set the namespace to remap to
            mixinRemap {
                disableRefmap() // like fabric-loom 1.6+
            }
        }
    }

    ```

=== "Kotlin"

    ```kotlin

    unimined.minecraft {
        ...
        defaultRemapJar = false // disable the default remapJar task

        remap(myJarTask) {
            prodNamespace("intermediary") // set the namespace to remap to
            mixinRemap {
                disableRefmap() // like fabric-loom 1.6+
            }
        }
    }

    ```
