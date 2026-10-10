package com.kidtoy

import com.kidtoy.geometry.Joints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JointsTest {
    @Test fun alienEarsAreFlaggedAndStrengthened() {
        val scene = AlienScene.make()
        val joints = Joints.analyse(scene, AlienScene.meshFor)
        for (j in joints) println("joint: ${scene[j.part].type}#${j.part} group=${j.group.size} ${String.format("%06X", scene[j.part].color and 0xFFFFFF)} -> #${j.other} " +
            "overlap ${"%.0f".format(j.overlapMm3)} mm3 neck ${"%.0f".format(j.neckMm2)} / need ${"%.0f".format(j.needMm2)} mm2${if (j.weak) "  WEAK" else ""}")
        val t0 = System.currentTimeMillis()
        val (fixed, moved) = Joints.strengthen(scene, AlienScene.meshFor)
        val after = Joints.weak(fixed, AlienScene.meshFor)
        println("joint: strengthen moved $moved parts in ${System.currentTimeMillis() - t0} ms, still weak: ${after.size}")
        assertEquals(scene.size, fixed.size)
        val weakBefore = Joints.weak(scene, AlienScene.meshFor)
        // exactly the two big ears hang on the head by a thin neck
        assertEquals(2, weakBefore.size)
        assertTrue(weakBefore.all { kotlin.math.abs(scene[it.part].position.x) > 35f })
        assertEquals(0, after.size)
        for (j in weakBefore) {
            val d = fixed[j.part].position - scene[j.part].position
            val dist = kotlin.math.sqrt(d.x * d.x + d.y * d.y + d.z * d.z)
            println("joint: ear #${j.part} moved ${"%.1f".format(dist)} mm towards the head")
            assertTrue(dist in 0.4f..8.1f)
            for (g in j.group) { val e = (fixed[g].position - scene[g].position) - d; assertTrue(kotlin.math.abs(e.x) + kotlin.math.abs(e.y) + kotlin.math.abs(e.z) < 1e-3f) }
        }
    }
}
