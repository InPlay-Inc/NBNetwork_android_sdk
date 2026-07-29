package com.nanobeaconnetwork

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestPolicyTest {

    @Test fun `library manifest leaves background location to host opt in`() {
        val manifestFile = File("src/main/AndroidManifest.xml")
        assertTrue("Library manifest not found at ${manifestFile.absolutePath}", manifestFile.isFile)

        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(manifestFile)

        val androidNamespace = "http://schemas.android.com/apk/res/android"
        val permissionNodes = document.getElementsByTagName("uses-permission")
        val declaresBackgroundLocation = (0 until permissionNodes.length).any { index ->
            permissionNodes.item(index)
                .attributes
                .getNamedItemNS(androidNamespace, "name")
                ?.nodeValue == "android.permission.ACCESS_BACKGROUND_LOCATION"
        }

        assertFalse(declaresBackgroundLocation)
    }
}
