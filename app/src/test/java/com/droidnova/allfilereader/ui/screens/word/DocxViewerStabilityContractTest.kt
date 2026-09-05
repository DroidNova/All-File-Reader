package com.droidnova.allfilereader.ui.screens.word
import java.io.File
import org.junit.Assert.*
import org.junit.Test
class DocxViewerStabilityContractTest{
 private val root=File(System.getProperty("user.dir")).let{if(File(it,"app").isDirectory)it else it.parentFile};private val js get()=File(root,"app/src/main/assets/docx_viewer/viewer.js").readText()
 @Test fun fetchAndRenderOccurExactlyOnce(){assertEquals(1,Regex("await fetch\\(").findAll(js).count());assertEquals(1,Regex("docx\\.renderAsync\\(").findAll(js).count());assertTrue(js.contains("renderAltChunks:false"));assertTrue(js.contains("data=null"))}
 @Test fun searchIsBoundedSafeAndGenerationGuarded(){assertTrue(js.contains("highlightLimit"));assertTrue(js.contains("searchGeneration"));assertTrue(js.contains("textContent"));assertFalse(js.contains("innerHTML"));assertTrue(js.contains("slice(0,256)"))}
 @Test fun progressAndCleanupAreFinite(){assertTrue(js.contains("MutationObserver"));assertTrue(js.contains("observer.disconnect"));assertTrue(js.contains("controller.abort"));assertTrue(js.contains("requestAnimationFrame(reportPage)"))}
}
