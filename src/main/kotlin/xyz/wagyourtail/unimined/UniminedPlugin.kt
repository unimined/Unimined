package xyz.wagyourtail.unimined

import org.gradle.api.Plugin
import org.gradle.api.Project
import xyz.wagyourtail.commonskt.utils.gb
import xyz.wagyourtail.unimined.api.UniminedExtension

class UniminedPlugin: Plugin<Project> {

    override fun apply(project: Project) {
        project.logger.lifecycle("[Unimined] Plugin Version: ${UniminedExtension.pluginVersion}")

        if (Runtime.getRuntime().maxMemory() < 2L.gb) {
            project.logger.warn("")
            project.logger.warn("[Unimined] You have less than 2GB of memory allocated to gradle.")
            project.logger.warn("[Unimined] This may cause issues with remapping and other tasks.")
            project.logger.warn("[Unimined] Please allocate more memory to gradle by adding: ")
            project.logger.warn("[Unimined]   org.gradle.jvmargs=-Xmx2G")
            project.logger.warn("[Unimined] to your gradle.properties file.")
            project.logger.warn("")
        }

        project.apply(
            mapOf(
                "plugin" to "java"
            )
        )
        project.apply(
            mapOf(
                "plugin" to "idea"
            )
        )

        project.extensions.create("unimined", UniminedExtensionImpl::class.java, project)
    }

}