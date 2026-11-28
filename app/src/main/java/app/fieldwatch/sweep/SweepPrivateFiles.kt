package app.fieldwatch.sweep

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions

/** Android uses owner-only modes. Providers without POSIX views inherit the private directory's ACL. */
internal object SweepPrivateFiles {
    private val directoryMode = PosixFilePermissions.fromString("rwx------")
    private val fileMode = PosixFilePermissions.fromString("rw-------")

    fun directory(directory: File) {
        val path = directory.toPath()
        val view = Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
        if (view != null) {
            Files.createDirectories(path, PosixFilePermissions.asFileAttribute(directoryMode))
            view.setPermissions(directoryMode)
        } else {
            Files.createDirectories(path)
            checkAccess(directory)
        }
    }

    fun temporary(directory: File, prefix: String): File {
        val path = directory.toPath()
        val file = if (Files.getFileAttributeView(path, PosixFileAttributeView::class.java) != null) {
            Files.createTempFile(path, prefix, ".tmp", PosixFilePermissions.asFileAttribute(fileMode)).toFile()
        } else {
            Files.createTempFile(path, prefix, ".tmp").toFile()
        }
        try {
            checkAccess(file)
        } catch (failure: IOException) {
            Files.deleteIfExists(file.toPath())
            throw failure
        }
        return file
    }

    private fun checkAccess(file: File) {
        if (!Files.isReadable(file.toPath()) || !Files.isWritable(file.toPath())) {
            throw IOException("Private storage is inaccessible.")
        }
    }
}
