import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Rewrites the handful of Java 9-11 APIs that jadx / ARSCLib / apksig call but that
 * Android only gained in API 28-35 (D8 does not backport them). Each call is redirected
 * to a static shim in `com.gt.apkforge.engine.compat.Jdk11Compat` so the in-process
 * engine runs on minSdk 26. `tools/check-dex-api.py` verifies the result in CI.
 */
abstract class ApiCompatTransform : AsmClassVisitorFactory<InstrumentationParameters.None> {
    override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor =
        object : ClassVisitor(Opcodes.ASM9, nextClassVisitor) {
            override fun visitMethod(
                access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?,
            ): MethodVisitor = CompatMethodVisitor(super.visitMethod(access, name, descriptor, signature, exceptions))
        }

    override fun isInstrumentable(classData: ClassData): Boolean =
        !classData.className.startsWith("com.gt.apkforge.engine.compat.")
}

private const val SHIM = "com/gt/apkforge/engine/compat/Jdk11Compat"

private class CompatMethodVisitor(mv: MethodVisitor) : MethodVisitor(Opcodes.ASM9, mv) {
    override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) {
        val javaOwner = owner.startsWith("java/") || owner.startsWith("javax/")
        if (javaOwner && opcode != Opcodes.INVOKESTATIC && opcode != Opcodes.INVOKESPECIAL) {
            // instance methods -> static shim taking the receiver as first argument
            val shim = when {
                name == "readAllBytes" && desc == "()[B" -> "(Ljava/io/InputStream;)[B"
                name == "readNBytes" && desc == "(I)[B" -> "(Ljava/io/InputStream;I)[B"
                name == "readNBytes" && desc == "([BII)I" -> "(Ljava/io/InputStream;[BII)I"
                name == "transferTo" && desc == "(Ljava/io/OutputStream;)J" -> "(Ljava/io/InputStream;Ljava/io/OutputStream;)J"
                name == "toString" && desc == "(Ljava/nio/charset/Charset;)Ljava/lang/String;" &&
                    owner == "java/io/ByteArrayOutputStream" -> "(Ljava/io/ByteArrayOutputStream;Ljava/nio/charset/Charset;)Ljava/lang/String;"
                name == "setInput" && desc == "(Ljava/nio/ByteBuffer;)V" &&
                    owner == "java/util/zip/Inflater" -> "(Ljava/util/zip/Inflater;Ljava/nio/ByteBuffer;)V"
                name == "getTypeName" && desc == "()Ljava/lang/String;" &&
                    owner == "java/lang/reflect/Type" -> "(Ljava/lang/reflect/Type;)Ljava/lang/String;"
                else -> null
            }
            if (shim != null) {
                super.visitMethodInsn(Opcodes.INVOKESTATIC, SHIM, name, shim, false)
                return
            }
        }
        if (owner == "jadx/core/utils/android/Res9patchStreamDecoder" && name == "decode") {
            // uses java.awt/ImageIO; the shim returns false so jadx copies the raw .9.png
            super.visitMethodInsn(Opcodes.INVOKESTATIC, SHIM, "decode9patch",
                "(Ljava/lang/Object;Ljava/io/InputStream;Ljava/io/OutputStream;)Z", false)
            return
        }
        if (opcode == Opcodes.INVOKESTATIC) {
            val shim = when {
                owner == "java/nio/file/Path" && name == "of" &&
                    desc == "(Ljava/lang/String;[Ljava/lang/String;)Ljava/nio/file/Path;" -> "pathOf"
                owner == "java/nio/file/Files" && name == "readString" &&
                    desc == "(Ljava/nio/file/Path;)Ljava/lang/String;" -> "readString"
                owner == "java/nio/file/Files" && name == "readString" &&
                    desc == "(Ljava/nio/file/Path;Ljava/nio/charset/Charset;)Ljava/lang/String;" -> "readString"
                owner == "java/nio/file/Files" && name == "writeString" -> "writeString"
                else -> null
            }
            if (shim != null) {
                super.visitMethodInsn(Opcodes.INVOKESTATIC, SHIM, shim, desc, false)
                return
            }
        }
        if (opcode == Opcodes.INVOKESPECIAL && owner == "java/io/PrintWriter" && name == "<init>") {
            // new PrintWriter(File|OutputStream, [autoFlush,] Charset) -> new PrintWriter(Writer[, true])
            when (desc) {
                "(Ljava/io/File;Ljava/nio/charset/Charset;)V" -> {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, SHIM, "writer",
                        "(Ljava/io/File;Ljava/nio/charset/Charset;)Ljava/io/Writer;", false)
                    super.visitMethodInsn(opcode, owner, name, "(Ljava/io/Writer;)V", false)
                    return
                }
                "(Ljava/io/OutputStream;ZLjava/nio/charset/Charset;)V" -> {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, SHIM, "writer",
                        "(Ljava/io/OutputStream;ZLjava/nio/charset/Charset;)Ljava/io/Writer;", false)
                    super.visitInsn(Opcodes.ICONST_1)
                    super.visitMethodInsn(opcode, owner, name, "(Ljava/io/Writer;Z)V", false)
                    return
                }
            }
        }
        super.visitMethodInsn(opcode, owner, name, desc, itf)
    }
}
