package xyz.wagyourtail.unimined.loadermerge.forgix

import io.github.pacifistmc.forgix.Forgix
import xyz.wagyourtail.unimined.loadermerge.ModLoaderMerger
import java.nio.file.Path

/**
 * [ModLoaderMerger] backed by Forgix (`io.github.pacifistmc:forgix`).
 * Selected via `loaderMerge { engine("forgix") }`, or automatically
 * when it is the only engine on the classpath.
 */
class ForgixLoaderMerger : ModLoaderMerger {

    override val name = "forgix"

    override fun merge(inputs: Map<String, Path>, output: Path) {
        Forgix.mergeLoaders(
            inputs.map { (loader, jar) -> jar.toFile() to loader }
                .toMap(java.util.HashMap()),
            output.toFile()
        )
    }

}
