package com.kidtoy

import com.kidtoy.geometry.Csg
import com.kidtoy.geometry.Solidify
import com.kidtoy.geometry.Transforms
import com.kidtoy.model.Shape
import com.kidtoy.model.ShapeType
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every part of the e2e alien really overlaps a neighbour (by volume, not just by bounding box). */
class AttachmentTest {
    @Test fun everyPartOverlapsANeighbour() {
        val scene = AlienScene.make()
        fun polys(s: Shape) = Csg.fromMesh(Transforms.toWorld(s, AlienScene.meshFor(s)), 0)
        fun vol(p: List<com.kidtoy.geometry.CsgPolygon>) = Solidify.weldAndTriangulate(p).volume()
        val boxes = scene.map { Transforms.worldBounds(it, AlienScene.meshFor(it)) }
        val vols = scene.map { vol(polys(it)) }
        // graph of real (volume) overlaps; the toy must be one connected piece
        val n = scene.size
        val inter = Array(n) { DoubleArray(n) }
        for (i in 0 until n) for (j in i + 1 until n) {
            if (!Solidify.overlap(boxes[i], boxes[j], 0.01f)) continue
            val v = vols[i] + vols[j] - vol(Csg.union(polys(scene[i]), polys(scene[j])))
            inter[i][j] = v; inter[j][i] = v
        }
        fun label(i: Int) = "${scene[i].type}#$i ${String.format("%06X", scene[i].color and 0xFFFFFF)}"
        for (i in 0 until n) println("attach: ${label(i)} -> " + (0 until n).filter { inter[i][it] > 0.5 }.joinToString { "${label(it)} ${"%.0f".format(inter[i][it])}mm3" })
        // a joint counts when it is >= 20 mm3, or >= 20% of the smaller part (tiny eye glints)
        fun joined(i: Int, j: Int) = inter[i][j] > minOf(MIN_OVERLAP_MM3, 0.2 * minOf(vols[i], vols[j]))
        val seen = BooleanArray(n); val stack = ArrayDeque(listOf(0)); seen[0] = true
        while (stack.isNotEmpty()) { val i = stack.removeLast(); for (j in 0 until n) if (!seen[j] && joined(i, j)) { seen[j] = true; stack.add(j) } }
        val loose = (0 until n).filter { !seen[it] }.map { label(it) }
        assertTrue("not attached: $loose", loose.isEmpty())
        // the big ears must hold on to the head itself, not only to their pink insides
        val head = (0 until n).filter { scene[it].type == ShapeType.SPHERE }.maxBy { vols[it] * (if (scene[it].position.y > 50f) 1 else 0) }
        val ears = (0 until n).filter { scene[it].type == ShapeType.SPHERE && kotlin.math.abs(scene[it].position.x) > 35f && scene[it].color == scene[head].color }
        for (e in ears) assertTrue("${label(e)} overlaps head by ${inter[e][head]} mm3", inter[e][head] > MIN_OVERLAP_MM3)
    }

    companion object { const val MIN_OVERLAP_MM3 = 20.0 }
}
