sourceSet("@@LOADER@@") {
    combineWith("main")
@@SIDE@@
    loader("@@LOADER_VERSION@@")
@@MAPPINGS@@
}
