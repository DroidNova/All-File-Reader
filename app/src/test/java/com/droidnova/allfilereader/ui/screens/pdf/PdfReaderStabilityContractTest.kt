package com.droidnova.allfilereader.ui.screens.pdf
import java.io.File
import org.junit.Assert.*
import org.junit.Test
class PdfReaderStabilityContractTest{
 private val root=File(System.getProperty("user.dir")).let{if(File(it,"app").isDirectory)it else it.parentFile}
 @Test fun viewerRemainsAndroidxFragmentAndLazyRenderingIsNotBypassed(){val screen=File(root,"app/src/main/java/com/droidnova/allfilereader/ui/screens/pdf/PdfReaderScreen.kt").readText();assertTrue(screen.contains("AndroidFragment<PdfViewerFragment>"));assertFalse(screen.contains("WebView"));assertFalse(screen.contains("Bitmap"));assertFalse(screen.contains("renderPage"))}
 @Test fun attemptsAndOwnedSourcesAreIsolated(){val vm=File(root,"app/src/main/java/com/droidnova/allfilereader/ui/screens/pdf/PdfReaderViewModel.kt").readText();assertTrue(vm.contains("++attempt"));assertTrue(vm.contains("id!=attempt"));assertTrue(vm.contains("job?.cancel()"));assertTrue(vm.contains("releaseSource"));assertFalse(vm.contains("readBytes()"));assertFalse(vm.contains("Base64"))}
 @Test fun searchUsesViewerApiWithoutReloadingSource(){val screen=File(root,"app/src/main/java/com/droidnova/allfilereader/ui/screens/pdf/PdfReaderScreen.kt").readText();assertTrue(screen.contains("isTextSearchActive"));assertEquals(1,Regex("documentUri=document.source.uri").findAll(screen.replace(" ","")).count())}
}
