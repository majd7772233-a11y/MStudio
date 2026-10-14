package dev.mutwakil.androidide.utils

import org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class FernflowerIntegrationTest {
    @Test
    fun decompilesCompiledJvmClassIntoReadableJavaSource() {
        // Android's boot classpath used by Kotlin test compilation does not expose
        // javax.tools at compile time. Resolve the host JDK compiler reflectively so
        // this JVM-only integration test can still compile with the Android plugin.
        val toolProvider = Class.forName("javax.tools.ToolProvider")
        val compiler = toolProvider.getMethod("getSystemJavaCompiler").invoke(null)
        assertTrue("Tests must run on a full JDK", compiler != null)

        val root = kotlin.io.path.createTempDirectory("mstudio-decompiler-test").toFile()
        try {
            val source = File(root, "Sample.java")
            source.writeText(
                "public class Sample { public int add(int left, int right) { return left + right; } }"
            )
            val classes = File(root, "classes").apply { mkdirs() }
            val run = Class.forName("javax.tools.JavaCompiler").getMethod(
                "run",
                InputStream::class.java,
                OutputStream::class.java,
                OutputStream::class.java,
                Array<String>::class.java
            )
            val compilationResult = run.invoke(
                compiler,
                null,
                null,
                null,
                arrayOf("-d", classes.absolutePath, source.absolutePath)
            ) as Int
            assertEquals(0, compilationResult)

            val output = File(root, "output").apply { mkdirs() }
            ConsoleDecompiler.main(arrayOf(File(classes, "Sample.class").absolutePath, output.absolutePath))
            val generated = output.walkTopDown().firstOrNull { it.isFile && it.name == "Sample.java" }
            assertTrue("Expected decompiled Sample.java", generated != null)
            val contents = generated!!.readText()
            assertTrue(contents.contains("class Sample"))
            assertTrue(contents.contains("add"))
        } finally {
            root.deleteRecursively()
        }
    }
}
