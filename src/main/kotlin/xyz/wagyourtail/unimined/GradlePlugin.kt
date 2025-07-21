package xyz.wagyourtail.unimined

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.launcher.GradleMain
import xyz.wagyourtail.commonskt.properties.FinalizeOnRead
import xyz.wagyourtail.commonskt.utils.g
import xyz.wagyourtail.unimined.api.UniminedExtension
import kotlin.jvm.java

class GradlePlugin : Plugin<Project> {
    val version by FinalizeOnRead(GradleMain::class.java.`package`.implementationVersion ?: "unknown")
    val logger: Logger = Logging.getLogger(GradleMain::class.java)

    init {
        logger.lifecycle("[xyz.wagyourtail.unimined] Loaded plugin verison: $version")

        if (Runtime.getRuntime().maxMemory() < 2L.g) {
            logger.warn("")
            logger.warn("[xyz.wagyourtail.unimined] You have less than 2GB of memory allocated to gradle.")
            logger.warn("[xyz.wagyourtail.unimined] This may cause issues with remapping and other tasks.")
            logger.warn("[xyz.wagyourtail.unimined] Please allocate more memory to gradle by adding:")
            logger.warn("[xyz.wagyourtail.unimined]   org.gradle.jvmargs=-Xmx2G")
            logger.warn("[xyz.wagyourtail.unimined] to your gradle.properties file.")
            logger.warn("")
        }

    }

    override fun apply(target: Project) {
        target.plugins.apply("idea")
        target.plugins.apply("java")

        target.extensions.create(
            "unimined",
            UniminedExtension::class.java
        )
    }

}