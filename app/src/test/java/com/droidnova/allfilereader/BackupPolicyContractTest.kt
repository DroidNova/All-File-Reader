package com.droidnova.allfilereader

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPolicyContractTest {
    private val root = File(System.getProperty("user.dir")).let { if (it.name == "app") it.parentFile else it }
    private val main = File(root, "app/src/main")
    private val androidNamespace = "http://schemas.android.com/apk/res/android"

    @Test fun applicationDisablesLegacyAndModernBackupWithoutChangingSecurityPolicy() {
        val manifest = parse(File(main, "AndroidManifest.xml"))
        val application = manifest.getElementsByTagName("application").item(0)
        assertEquals("false", application.attributes.getNamedItemNS(androidNamespace, "allowBackup").nodeValue)
        assertEquals("false", application.attributes.getNamedItemNS(androidNamespace, "fullBackupContent").nodeValue)
        assertEquals("@xml/data_extraction_rules", application.attributes.getNamedItemNS(androidNamespace, "dataExtractionRules").nodeValue)
        assertEquals("false", application.attributes.getNamedItemNS(androidNamespace, "usesCleartextTraffic").nodeValue)
        assertEquals("true", application.attributes.getNamedItemNS(androidNamespace, "supportsRtl").nodeValue)
    }

    @Test fun noManifestOverlayCanEnableBackup() {
        val manifests = File(root, "app/src").walkTopDown().filter { it.isFile && it.name == "AndroidManifest.xml" }.toList()
        assertEquals(listOf(File(main, "AndroidManifest.xml")), manifests)
        manifests.forEach { manifest ->
            val application = parse(manifest).getElementsByTagName("application").item(0)
            assertFalse(application?.attributes?.getNamedItemNS(androidNamespace, "allowBackup")?.nodeValue == "true")
        }
    }

    @Test fun modernRulesExcludeEverySupportedDomainFromCloudAndTransfer() {
        val rules = parse(File(main, "res/xml/data_extraction_rules.xml"))
        val expected = setOf(
            "root", "file", "database", "sharedpref", "external",
            "device_root", "device_file", "device_database", "device_sharedpref"
        )
        listOf("cloud-backup", "device-transfer").forEach { sectionName ->
            val section = rules.getElementsByTagName(sectionName).item(0)
            val excludes = section.childNodes.asSequence()
                .filter { it.nodeName == "exclude" }
                .associate { it.attributes.getNamedItem("domain").nodeValue to it.attributes.getNamedItem("path").nodeValue }
            assertEquals(expected, excludes.keys)
            assertTrue(excludes.values.all { it == "." })
        }
        assertEquals(0, rules.getElementsByTagName("include").length)
    }

    @Test fun favoritesRemainLocalDataStoreAndNoStartupClearWasAdded() {
        val source = File(main, "java/com/droidnova/allfilereader/data/repository/DataStoreFavoritesRepository.kt").readText()
        assertTrue(source.contains("preferencesDataStore(name = \"document_favorites\")"))
        assertTrue(source.contains("stringSetPreferencesKey(\"favorite_document_ids\")"))
        assertFalse(source.contains("deleteFile("))
        assertFalse(source.contains("clearDataStore"))
    }

    @Test fun readerCopiesRemainInCacheAndFileProviderScopeIsNotBroadened() {
        val source = File(main, "java").walkTopDown().filter { it.isFile && it.extension == "kt" }.joinToString("\n") { it.readText() }
        listOf("pdf_sessions", "docx_sessions", "spreadsheet_sessions", "pptx_sessions", "txt_reader_sessions").forEach {
            assertTrue("Missing cache-backed session directory $it", source.contains(it))
        }
        assertFalse(source.contains("File(context.filesDir"))
        val paths = parse(File(main, "res/xml/legacy_ppt_file_paths.xml"))
        assertEquals(1, paths.getElementsByTagName("cache-path").length)
        assertEquals(0, paths.getElementsByTagName("files-path").length)
        assertEquals(0, paths.getElementsByTagName("external-path").length)
    }

    @Test fun documentSnapshotRemainsProcessMemoryOnly() {
        val source = File(main, "java/com/droidnova/allfilereader/data/repository/MediaStoreDocumentRepository.kt").readText()
        assertTrue(source.contains("private var cache: List<DocumentFile>?"))
        assertTrue(source.contains("MutableStateFlow<List<DocumentFile>>"))
        assertFalse(source.contains("preferencesDataStore"))
        assertFalse(source.contains("SharedPreferences"))
        assertFalse(source.contains("Room.databaseBuilder"))
        assertFalse(source.contains("filesDir"))
    }

    private fun parse(file: File) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(file)

    private fun org.w3c.dom.NodeList.asSequence() = sequence {
        for (index in 0 until length) yield(item(index))
    }
}
