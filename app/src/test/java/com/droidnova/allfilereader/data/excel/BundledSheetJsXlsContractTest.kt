package com.droidnova.allfilereader.data.excel

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class BundledSheetJsXlsContractTest {
    @Test fun exactBundledAssetParsesKnownXlsFixtures() {
        val root = File(System.getProperty("user.dir")).let { if (File(it, "app").isDirectory) it else it.parentFile }
        val nodeAvailable = runCatching { ProcessBuilder("node", "--version").start().waitFor(5, TimeUnit.SECONDS) }.getOrDefault(false)
        assumeTrue("Node is required to execute the exact browser-compatible SheetJS asset", nodeAvailable)
        val process = ProcessBuilder("node", "app/src/test/scripts/verify-bundled-sheetjs-xls.js")
            .directory(root).redirectErrorStream(true).start()
        assertTrue("SheetJS contract timed out", process.waitFor(30, TimeUnit.SECONDS))
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 0, process.exitValue())
        assertTrue(output, output.contains("XLS contract: PASS"))
    }
}
