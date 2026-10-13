package dev.mutwakil.androidide.utils

/** Input limits for the embedded JVM bytecode decompiler; APK/DEX is not JVM bytecode. */
object DecompilerInputPolicy {
    const val MAX_INPUT_BYTES: Long = 128L * 1024L * 1024L
    const val MAX_LISTED_FILES: Int = 100

    fun isSupportedFileName(name: String): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase()
        return extension == "class" || extension == "jar" || extension == "zip"
    }

    fun isWithinSizeLimit(sizeBytes: Long): Boolean =
        sizeBytes in 0..MAX_INPUT_BYTES
}
