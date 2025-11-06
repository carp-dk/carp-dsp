package carp.dsp.demo

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.pathString

// actual fun createTempFilePath(prefix: String, suffix: String): String {
//    val tempDir = System.getProperty("java.io.tmpdir")
//    val tempFile = Files.createTempFile(Path.of(tempDir), prefix, suffix)
//
//    // Delete the file immediately since we only want the path
//    // The actual file will be created when needed
//    Files.deleteIfExists(tempFile)
//
//    return tempFile.pathString
// }
