minecraft {
        version = "1.20.1"

        loader("fabric", "0.16.0")
        mappings {
            yarn("1.20.1+build.10")
        }
    }

    mod {
        id = "examplemod"
        name = "examplemod"
        version = "1.0.0"
        loader("fabric") { }
    }
}

dependencies {
    // mods on the classpath — remapped onto your mappings
    // modImplementation("maven:coord")
    // modCompileOnly(...) / modLocalRuntime(...)
    // unimined.loaderApi("fabric", "0.92.2+1.20.1") — loader api coords
    // (also records the api modid into mod { depends } automatically)
}
