package com.droidnova.allfilereader.ui.screens.powerpoint
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
class PptxAssetContractTest {
 private val root=File(System.getProperty("user.dir")).let{if(it.name=="app")it.parentFile else it}
 @Test fun bundleIsCanonical(){val f=File(root,"app/src/main/assets/pptx_viewer/aiden0z-pptx-renderer.browser.es.js");val h=MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString(""){"%02x".format(it)};assertEquals("31cf1e39818c52395b185186229f80ecf8333db0d7bb3a06f6c0bd74b87aaad5",h)}
 @Test fun viewerSecurityAndLifecycleContract(){val html=File(root,"app/src/main/assets/pptx_viewer/viewer.html").readText();val js=File(root,"app/src/main/assets/pptx_viewer/viewer.js").readText();assertTrue(html.contains("Content-Security-Policy"));assertTrue(js.contains("fitMode:'contain'"));assertTrue(js.contains("searchText"));assertTrue(js.contains("viewer?.destroy()"));assertFalse(js.contains("http://"));assertFalse(js.contains("https://"))}
}
