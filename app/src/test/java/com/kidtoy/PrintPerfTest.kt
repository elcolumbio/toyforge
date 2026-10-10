package com.kidtoy

import com.kidtoy.geometry.Solidify
import com.kidtoy.io.PrintPackager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The print dialog must answer instantly and the exact solid must respect its time budget. */
class PrintPerfTest {
    private val scene by lazy { AlienScene.make() }

    @Test fun quickRecipeIsFastAndAgreesWithTheExactOne() {
        PrintPackager.quick(scene, AlienScene.meshFor) // warm-up
        val t0 = System.currentTimeMillis()
        val q = PrintPackager.quick(scene, AlienScene.meshFor)
        val quickMs = System.currentTimeMillis() - t0
        assertFalse(q.refined)
        val full = PrintPackager.build(scene, AlienScene.meshFor, quickPkg = q)
        println("perf: quick $quickMs ms, quick=${q.plan}, full=${full.plan}")
        assertTrue("quick took $quickMs ms", quickMs < 1500)
        assertEquals(full.plan.supports, q.plan.supports)
        assertEquals(full.plan.brim, q.plan.brim)
        assertEquals(0, q.solid.mesh.openEdgeCount())
    }

    @Test fun fuseRespectsItsTimeBudgetAndStaysClosed() {
        for (budget in listOf(1L, 50L, 300L)) {
            val t0 = System.currentTimeMillis()
            val r = Solidify.run(scene, AlienScene.meshFor, timeBudgetMs = budget)
            val ms = System.currentTimeMillis() - t0
            println("perf: budget $budget -> $ms ms fused=${r.fusedGroups} fallback=${r.fallbackGroups}")
            assertTrue("budget $budget took $ms ms", ms < budget + 1500)
            assertEquals(0, r.mesh.openEdgeCount())
            assertTrue(r.mesh.volume() > 0)
        }
    }
}
