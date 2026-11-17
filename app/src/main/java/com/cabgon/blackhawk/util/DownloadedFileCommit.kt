package com.cabgon.blackhawk.util

import java.io.File
import java.io.IOException

/** All files share a directory so commit is a checked rename, never an overwrite-copy. */
object DownloadedFileCommit {
    @Synchronized
    fun recover(finalFile: File) {
        val backup = File(finalFile.path + ".bak")
        if (!finalFile.exists() && backup.exists() && !backup.renameTo(finalFile)) {
            throw IOException("No se pudo recuperar el archivo anterior")
        }
    }

    @Synchronized
    fun commit(tempFile: File, finalFile: File) {
        require(tempFile.parentFile?.canonicalFile == finalFile.parentFile?.canonicalFile)
        if (!tempFile.isFile || tempFile.length() <= 0L) {
            throw IOException("La descarga terminó sin contenido")
        }
        recover(finalFile)
        val backup = File(finalFile.path + ".bak")
        if (backup.exists() && !backup.delete()) {
            throw IOException("No se pudo preparar el respaldo del archivo")
        }
        val hadPrevious = finalFile.exists()
        if (hadPrevious && !finalFile.renameTo(backup)) {
            throw IOException("No se pudo respaldar el archivo anterior")
        }
        try {
            if (!tempFile.renameTo(finalFile)) throw IOException("No se pudo guardar la descarga")
        } catch (error: IOException) {
            if (hadPrevious && !backup.renameTo(finalFile)) {
                error.addSuppressed(IOException("El archivo anterior permanece en su respaldo"))
            }
            throw error
        }
        if (backup.exists()) backup.delete()
    }
}
