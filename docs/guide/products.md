# Products

The *product* is which Minecraft distribution an env runs — orthogonal
to the loader. `minecraft { }` is the native product; others get their
own blocks:

```kotlin
unimined {
    bta { loader("bta-babric", "1.7.6") }       // Better Than Adventure
    reindev { loader("foxloader", "…") }        // ReIndev + FoxLoader
    nsss { file("dist.jar") }                   // Not So Seecret Saturday
    custom("myproduct") { file("custom.jar") }  // arbitrary dist
}
```

--8<-- "generated/product-bta.gradle.kts"

Products nest their loader inside the product env — the loader axis
stays the same either way.
