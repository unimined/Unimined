# Remap Pipeline

`unimined { remap { } }` configures the remapping steps — the verbs are
ordered pipeline steps applied to the MC jar and remapped mod deps:

```kotlin
unimined {
    remap {
        unpick("path/to.unpick")
        applyClassTweaker { /* custom bytecode transform */ }
        sourceRemap { /* UML source remapper — the default engine */ }
        jst { /* explicit opt-in: Java Source Transform */ }
    }
}
```

Source remapping runs through UML's source remapper by default;
`jst { }` remains as an explicit engine opt-in for verification or
alternate-engine use.
