package dev.mutwakil.androidide.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecompilerInputPolicyTest {
    @Test
    fun acceptsJvmClassAndArchivesCaseInsensitively() {
        assertTrue(DecompilerInputPolicy.isSupportedFileName("Sample.class"))
        assertTrue(DecompilerInputPolicy.isSupportedFileName("library.JAR"))
        assertTrue(DecompilerInputPolicy.isSupportedFileName("bundle.zip"))
    }

    @Test
    fun rejectsAndroidPackagesAndUnrecognizedFiles() {
        assertFalse(DecompilerInputPolicy.isSupportedFileName("application.apk"))
        assertFalse(DecompilerInputPolicy.isSupportedFileName("classes.dex"))
        assertFalse(DecompilerInputPolicy.isSupportedFileName("source.java"))
        assertFalse(DecompilerInputPolicy.isSupportedFileName("unknown"))
    }

    @Test
    fun boundsArchiveEntryCountAndExpandedSize() {
        assertTrue(DecompilerInputPolicy.isArchiveWithinLimits(0, 0))
        assertTrue(DecompilerInputPolicy.isArchiveWithinLimits(
            DecompilerInputPolicy.MAX_ARCHIVE_ENTRIES,
            DecompilerInputPolicy.MAX_EXPANDED_ARCHIVE_BYTES
        ))
        assertFalse(DecompilerInputPolicy.isArchiveWithinLimits(
            DecompilerInputPolicy.MAX_ARCHIVE_ENTRIES + 1, 0
        ))
        assertFalse(DecompilerInputPolicy.isArchiveWithinLimits(
            1, DecompilerInputPolicy.MAX_EXPANDED_ARCHIVE_BYTES + 1
        ))
    }

    @Test
    fun enforcesNonNegativeInputSizeLimit() {
        assertTrue(DecompilerInputPolicy.isWithinSizeLimit(0))
        assertTrue(DecompilerInputPolicy.isWithinSizeLimit(DecompilerInputPolicy.MAX_INPUT_BYTES))
        assertFalse(DecompilerInputPolicy.isWithinSizeLimit(-1))
        assertFalse(DecompilerInputPolicy.isWithinSizeLimit(DecompilerInputPolicy.MAX_INPUT_BYTES + 1))
    }
}
