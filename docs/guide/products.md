# Products

The *product* is which Minecraft distribution an env runs — orthogonal
to the loader. `minecraft { }` is the native product; others get their
own blocks:

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    unimined {
        bta { loader("bta-babric", "1.7.6") }       // Better Than Adventure
        reindev { loader("foxloader", "…") }        // ReIndev + FoxLoader
        nsss { file("dist.jar") }                   // Not So Seecret Saturday
        custom("myproduct") { file("custom.jar") }  // arbitrary dist
    }
    ```

=== "Groovy `build.gradle`"
    ```groovy
    unimined {
        bta { loader("bta-babric", "1.7.6") }       // Better Than Adventure
        reindev { loader("foxloader", "…") }        // ReIndev + FoxLoader
        nsss { file("dist.jar") }                   // Not So Seecret Saturday
        custom("myproduct") { file("custom.jar") }  // arbitrary dist
    }
    ```

Products nest their loader inside the product env — the loader axis
stays the same either way.

Full generated build scripts: [BTA](../generated/product-bta.gradle.kts)
/ [custom product](../generated/product-custom.gradle.kts) (`.gradle`
siblings are the Groovy twins).
