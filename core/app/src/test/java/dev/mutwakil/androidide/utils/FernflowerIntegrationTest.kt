package dev.mutwakil.androidide.utils

import org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.tools.ToolProvider

class FernflowerIntegrationTest {
    @Test
    fun decompilesCompiledJvmClassIntoReadableJavaSource() {
        val compiler = ToolProvider.getSystemJavaCompiler()
        assertTrue("Tests must run on a full JDK", compiler != null)
        val root = kotlin.io.path.createTempDirectory("mstudio-decompiler-test").toFile()
        try {
            val source = File(root, "Sample.java")
            source.writeText(
                "public class Sample { public int add(int left, int right) { return left + right; } }"
            )
            val classes = File(root, "classes").apply { mkdirs() }
            assertEquals(0, compiler!!.run(null, null, null, "-d", classes.absolutePath, source.absolutePath))
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
