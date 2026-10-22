package io.github.excludebarrier.orsikrelay.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayFrameTest {

    @Test
    fun atRestOneRunnerHoldsTheBatonAndNothingMoves() {
        val a = relayFrame(0L, RelayPhase.Working, still = true)
        val b = relayFrame(12_345L, RelayPhase.Tool, still = true)

        assertEquals(a, b)
        assertEquals(listOf(1f, REST_GLOW, REST_GLOW), a.glow)
        assertEquals(0f, a.arcSweep)
    }

    @Test
    fun theBatonPassesToTheNextRunnerEveryHandOff() {
        val hand = RelayPhase.Working.handOffMs
        assertEquals(0, relayFrame(10L, RelayPhase.Working).holder)
        assertEquals(1, relayFrame(hand + 10L, RelayPhase.Working).holder)
        assertEquals(2, relayFrame(2 * hand + 10L, RelayPhase.Working).holder)
        assertEquals(0, relayFrame(3 * hand + 10L, RelayPhase.Working).holder)
    }

    @Test
    fun duringTheHandOffTheLightMovesFromHolderToNextAndTheArcGrows() {
        val hand = RelayPhase.Working.handOffMs
        val early = relayFrame((hand * 0.2f).toLong(), RelayPhase.Working)
        val late = relayFrame((hand * 0.95f).toLong(), RelayPhase.Working)

        assertEquals(0f, early.arcSweep)
        assertEquals(1f, early.glow[0])
        assertTrue(late.arcSweep > 0f)
        assertTrue(late.glow[1] > late.glow[2])
        assertTrue(late.glow[0] < 1f)
    }

    @Test
    fun thinkingIsSlowerAndCloserToolIsFaster() {
        assertTrue(RelayPhase.Thinking.handOffMs > RelayPhase.Working.handOffMs)
        assertTrue(RelayPhase.Tool.handOffMs < RelayPhase.Working.handOffMs)
        assertTrue(RelayPhase.Thinking.orbit < RelayPhase.Working.orbit)
        assertTrue(RelayPhase.Tool.arc > RelayPhase.Working.arc)
    }

    @Test
    fun theRunnersStayAThirdOfACircleApart() {
        val frame = relayFrame(777L, RelayPhase.Tool)
        val gap = (frame.angles[1] - frame.angles[0]).toDouble()

        assertEquals(2 * Math.PI / 3, gap, 1e-4)
    }
}
