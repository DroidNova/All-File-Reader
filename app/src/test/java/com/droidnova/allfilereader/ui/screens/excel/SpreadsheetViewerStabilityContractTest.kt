package com.droidnova.allfilereader.ui.screens.excel

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class SpreadsheetViewerStabilityContractTest {
    private val root=File(System.getProperty("user.dir")).let{if(File(it,"app").isDirectory)it else it.parentFile}
    private val js get()=File(root,"app/src/main/assets/excel_viewer/viewer.js").readText()
    @Test fun workbookIsFetchedAndParsedOnlyOnce(){assertEquals(1,Regex("XLSX\\.read\\(").findAll(js).count());assertEquals(1,Regex("await fetch\\(").findAll(js).count());assertTrue(js.contains("data=null"))}
    @Test fun rangeAndDomWorkAreBounded(){assertTrue(js.contains("safeRange"));assertTrue(js.contains("safeCount"));assertTrue(js.contains("surface.replaceChildren"));assertTrue(js.contains("pageRows"));assertFalse(js.contains("sheet_to_html"))}
    @Test fun cellAndSheetTextUseSafeDomApis(){assertTrue(js.contains("e.textContent=text"));assertTrue(js.contains("b.textContent=name"));assertFalse(js.contains("innerHTML"));assertTrue(js.contains("bookVBA:false"))}
    @Test fun progressAndRapidRenderInvalidationArePresent(){assertTrue(js.contains("state.progress++"));assertTrue(js.contains("renderGeneration"));assertTrue(js.contains("generation!==renderGeneration"))}
}
