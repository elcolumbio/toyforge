package com.kidtoy

import com.kidtoy.geometry.Primitives
import com.kidtoy.io.MeshImporter
import com.kidtoy.library.HttpGet
import com.kidtoy.library.LibraryContent
import com.kidtoy.library.LibraryException
import com.kidtoy.library.SmithsonianLibrary
import com.kidtoy.library.StarterLibrary
import com.kidtoy.model.ShapeType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LibraryTest {
    private val fixture = javaClass.classLoader!!.getResource("si_search.json")!!.readText()

    @Test fun smithsonianSearchKeepsOnlyCc0ObjPackages() = runBlocking {
        var url = ""
        val lib = SmithsonianLibrary("KEY", HttpGet { u -> url = u; 200 to fixture.toByteArray() })
        val items = lib.search("dinosaur")
        assertTrue(url.startsWith("https://api.si.edu/openaccess/api/v1.0/search?q=dinosaur+3d"))
        assertTrue(url.endsWith("api_key=KEY"))
        assertEquals(1, items.size) // the non-3D row is dropped
        val it = items[0]
        assertEquals("CC0 (Smithsonian Open Access)", it.license)
        assertTrue(it.downloadUrl!!.endsWith("100k-obj.zip"))
    }

    @Test fun smithsonianDownloadErrorsAreKidFriendly() = runBlocking {
        val lib = SmithsonianLibrary("K", HttpGet { throw javax.net.ssl.SSLHandshakeException("certificate expired") })
        try { lib.search("x"); fail() } catch (e: LibraryException) { assertTrue(e.kidMessage.contains("museum")) }
    }

    @Test fun zippedObjImports() {
        // OBJ cube inside a zip with an extra texture file, like the museum packages
        val m = Primitives.get(ShapeType.CUBE)
        val obj = StringBuilder()
        for (i in 0 until m.positions.size / 3) obj.append("v ${m.positions[i * 3]} ${m.positions[i * 3 + 1]} ${m.positions[i * 3 + 2]}\n")
        for (t in 0 until m.triangleCount) obj.append("f ${t * 3 + 1} ${t * 3 + 2} ${t * 3 + 3}\n")
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("tex.jpg")); z.write(ByteArray(10)); z.closeEntry()
            z.putNextEntry(ZipEntry("model/cube.obj")); z.write(obj.toString().toByteArray()); z.closeEntry()
        }
        val mesh = MeshImporter.read("pkg.zip", bos.toByteArray())
        assertEquals(12, mesh.triangleCount)
        val fetched = runBlocking { SmithsonianLibrary("K", HttpGet { 200 to bos.toByteArray() }).fetch(
            com.kidtoy.library.LibraryItem("i", "t", "e", "CC0", downloadUrl = "https://x/y-100k-obj.zip")) }
        assertTrue(fetched is LibraryContent.MeshFile)
    }

    @Test fun starterLibraryListsAndFetches() = runBlocking {
        val lib = StarterLibrary()
        val items = lib.search("")
        assertTrue(items.size >= 6)
        val c = lib.fetch(items.first { it.id == "rocket" })
        assertTrue(c is LibraryContent.Shapes && c.shapes.isNotEmpty())
    }
}

class MuseumAvailabilityTest {
    @Test fun brokenTlsHidesTheMuseumButOfflineDoesNot() = kotlinx.coroutines.runBlocking {
        val tls = com.kidtoy.library.SmithsonianLibrary(http = { throw javax.net.ssl.SSLHandshakeException("PKIX path validation failed: certificate expired") })
        org.junit.Assert.assertFalse(tls.isAvailable())
        val wrapped = com.kidtoy.library.SmithsonianLibrary(http = { throw java.io.IOException("x", java.security.cert.CertificateExpiredException("expired")) })
        org.junit.Assert.assertFalse(wrapped.isAvailable())
        val offline = com.kidtoy.library.SmithsonianLibrary(http = { throw java.net.UnknownHostException("3d-api.si.edu") })
        org.junit.Assert.assertTrue(offline.isAvailable())
        val ok = com.kidtoy.library.SmithsonianLibrary(http = { 404 to ByteArray(0) })
        org.junit.Assert.assertTrue(ok.isAvailable())
        org.junit.Assert.assertTrue(com.kidtoy.library.StarterLibrary().isAvailable())
    }
}
