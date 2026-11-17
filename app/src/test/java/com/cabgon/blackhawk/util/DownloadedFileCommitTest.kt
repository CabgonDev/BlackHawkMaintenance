package com.cabgon.blackhawk.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class DownloadedFileCommitTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun commitReplacesPreviousFileOnlyAfterDownloadExists() {
        val final = folder.newFile("manual.pdf").apply { writeText("previous") }
        val temp = folder.newFile("download.part").apply { writeText("downloaded") }
        DownloadedFileCommit.commit(temp, final)
        assertEquals("downloaded", final.readText())
        assertFalse(temp.exists())
        assertFalse(File(final.path + ".bak").exists())
    }

    @Test fun invalidDownloadPreservesPreviousFile() {
        val final = folder.newFile("index.db").apply { writeText("previous") }
        val empty = folder.newFile("download.part")
        try { DownloadedFileCommit.commit(empty, final) } catch (_: IOException) { }
        assertEquals("previous", final.readText())
    }

    @Test fun processInterruptionRecoversBackupWhenFinalIsMissing() {
        val final = File(folder.root, "manual.pdf")
        File(final.path + ".bak").writeText("previous")
        DownloadedFileCommit.recover(final)
        assertEquals("previous", final.readText())
    }
}
