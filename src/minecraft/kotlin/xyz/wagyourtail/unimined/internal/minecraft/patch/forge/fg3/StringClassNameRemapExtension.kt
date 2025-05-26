package xyz.wagyourtail.unimined.internal.minecraft.patch.forge.fg3

import net.fabricmc.tinyremapper.TinyRemapper
import net.fabricmc.tinyremapper.api.TrClass
import net.fabricmc.tinyremapper.extension.mixin.common.Logger
import net.fabricmc.tinyremapper.extension.mixin.common.data.Constant
import org.gradle.api.logging.LogLevel
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import xyz.wagyourtail.unimined.internal.mapping.extension.MixinRemapExtension

class StringClassNameRemapExtension(
    loggerLevel: LogLevel = LogLevel.WARN,
    val classFilter: (String) -> Boolean = { true }
) : TinyRemapper.Extension, TinyRemapper.ApplyVisitorProvider {

    private val logger: Logger = Logger(MixinRemapExtension.translateLogLevel(loggerLevel))
    override fun attach(builder: TinyRemapper.Builder) {
        builder.extraPreApplyVisitor(this)
    }

    val classDescRegex = Regex("L([^;]+);")
    override fun insertApplyVisitor(cls: TrClass, next: ClassVisitor): ClassVisitor {
        if (!classFilter(cls.name)) return next
        return object : ClassVisitor(Constant.ASM_VERSION, next) {

            override fun visitMethod(
                access: Int,
                name: String?,
                descriptor: String?,
                signature: String?,
                exceptions: Array<out String>?
            ): MethodVisitor {
                return object : MethodVisitor(Constant.ASM_VERSION, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                    override fun visitLdcInsn(value: Any?) {
                        super.visitLdcInsn(
                            if (value is String) {
                                value.replace(classDescRegex) {
                                    val className = it.groupValues[1]
                                    val remapped = cls.environment.remapper.map(className)
                                    if (remapped != className) {
                                        logger.info("[Unimined/TR-StringClassNameRemapper] Remapped $className to $remapped in a string")
                                        "L$remapped;"
                                    } else {
                                        it.value
                                    }
                                }
                                if (value.contains('.') && value.contains('/')) {
                                    // not a class name, would break in remapping
                                    value
                                } else {
                                    val dotty = value.contains('.')
                                    val remapped = cls.environment.remapper.map(value.replace('.', '/'))
                                        .let { if (dotty) it.replace('/', '.') else it }
                                    if (remapped != value) {
                                        logger.info("[Unimined/TR-StringClassNameRemapper] Remapped $value to $remapped in a string")
                                        remapped
                                    } else {
                                        value
                                    }
                                }
                            } else {
                                value
                            }
                        )
                    }
                }
            }

        }
    }


}
