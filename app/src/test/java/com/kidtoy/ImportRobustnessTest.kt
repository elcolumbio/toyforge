package com.kidtoy

import com.kidtoy.io.ImportException
import com.kidtoy.io.MeshImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/** Broken, hostile and huge model files must never crash the app: either a mesh or an ImportException. */
class ImportRobustnessTest {
    private fun binaryStl(tris: List<FloatArray>, declared: Int = tris.size, trailing: Int = 0): ByteArray {
        val bb = ByteBuffer.allocate(84 + 50 * tris.size + trailing).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(80); bb.putInt(declared)
        for (t in tris) { repeat(3) { bb.putFloat(0f) }; t.forEach { bb.putFloat(it) }; bb.putShort(0) }
        return bb.array()
    }
    private val tri = floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f)

    private fun rejects(name: String, bytes: ByteArray) {
        try { MeshImporter.read(name, bytes); fail("accepted $name") } catch (e: ImportException) { /* ok */ }
    }

    @Test fun emptyAndGarbageAreRejectedCleanly() {
        rejects("a.stl", ByteArray(0))
        rejects("a.obj", ByteArray(0))
        val r = Random(1)
        repeat(50) { i -> rejects(if (i % 2 == 0) "x.stl" else "x.obj", r.nextBytes(r.nextInt(1, 5000))) }
        rejects("x.zip", byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4, 0, 0, 0))
    }

    @Test fun brokenObjIsAnImportException() {
        rejects("a.obj", "v 1 2\nf 1 2 3\n".toByteArray())
        rejects("a.obj", "v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 x\n".toByteArray())
        rejects("a.obj", "v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 9\n".toByteArray())
        rejects("a.obj", "v a b c\nf 1 2 3\n".toByteArray())
    }

    @Test fun nanAndDegenerateTrianglesAreDropped() {
        val bad = floatArrayOf(Float.NaN, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f)
        val flat = floatArrayOf(0f, 0f, 0f, 1f, 1f, 1f, 2f, 2f, 2f)
        val m = MeshImporter.read("a.stl", binaryStl(listOf(tri, bad, flat)))
        assertEquals(1, m.positions.size / 9)
        rejects("b.stl", binaryStl(listOf(bad, flat)))
    }

    @Test fun binaryStlWithTrailingBytesOrSolidHeaderStillLoads() {
        assertEquals(1, MeshImporter.read("a.stl", binaryStl(listOf(tri), trailing = 2)).positions.size / 9)
        // binary files whose 80-byte header starts with "solid" (SolidWorks does this)
        val b = binaryStl(listOf(tri, tri.map { it + 1f }.toFloatArray()))
        "solid part".toByteArray().copyInto(b)
        assertEquals(2, MeshImporter.read("a.stl", b).positions.size / 9)
    }

    @Test fun truncatedBinaryStlDoesNotCrash() {
        val b = binaryStl(List(100) { tri }).copyOf(84 + 50 * 40 + 7)
        try { MeshImporter.read("a.stl", b) } catch (e: ImportException) { /* fine either way */ }
    }

    @Test fun hugeFilesAreRefusedEarly() {
        val bb = ByteBuffer.allocate(84).order(ByteOrder.LITTLE_ENDIAN); bb.position(80); bb.putInt(MeshImporter.MAX_TRIANGLES + 1)
        val head = bb.array()
        val big = head + ByteArray(50 * (MeshImporter.MAX_TRIANGLES + 1))
        rejects("big.stl", big)
        try { MeshImporter.readLimited(java.io.ByteArrayInputStream(ByteArray(2000)), max = 1000); fail() } catch (e: ImportException) { }
        assertEquals(1000, MeshImporter.readLimited(java.io.ByteArrayInputStream(ByteArray(1000)), max = 1000).size)
    }

    @Test fun largeValidStlIsFast() {
        val r = Random(2)
        val tris = List(200_000) { FloatArray(9) { r.nextFloat() * 100f } }
        val t0 = System.nanoTime()
        val m = MeshImporter.read("big.stl", binaryStl(tris))
        val ms = (System.nanoTime() - t0) / 1e6
        println("import 200k triangles: %.0f ms".format(ms))
        assertEquals(200_000, m.positions.size / 9)
        assertTrue(ms < 5000)
    }
}
