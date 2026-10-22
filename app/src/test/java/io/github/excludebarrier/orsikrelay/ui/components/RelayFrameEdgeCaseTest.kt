package io.github.excludebarrier.orsikrelay.ui.components

import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure relay frame maths at its arithmetic edges: the clock is a Long
 * that starts at zero, but neither a negative value nor Long.MAX_VALUE may
 * break the holder index or produce non-finite geometry.
 */
class RelayFrameEdgeCaseTest {

    @Test
    fun zeroTimeStartsAtTheTopWithTheFirstRunnerHoldingTheBaton() {
        val frame = relayFrame(0L, RelayPhase.Working)

        assertEquals(0, frame.holder)
        assertEquals(listOf(1f, REST_GLOW, REST_GLOW), frame.glow)
        assertEquals(0f, frame.arcSweep, 0f)
        assertEquals(0f, frame.starAngle, 0f)
        assertEquals((-PI / 2).toFloat(), frame.angles[0], 1e-6f)
        assertEquals(3, frame.angles.size)
    }

    @Test
    fun aSlightlyNegativeClockGivesAValidFrame() {
        listOf(-1L, -599L).forEach { time ->
            val frame = relayFrame(time, RelayPhase.Working)

            // Only validity is required for a negative clock (floorMod keeps
            // the frame in range); which runner holds the baton is free.
            assertTrue("holder at $time", frame.holder in 0..2)
            assertTrue("glow at $time", frame.glow.all { it in REST_GLOW..1f })
            assertTrue("sweep at $time", frame.arcSweep >= 0f)
            assertTrue("angles at $time", frame.angles.all { it.isFinite() })
        }
    }

    @Test
    fun aNegativeClockBelowTheFirstHandOffKeepsTheHolderInRange() {
        listOf(-600L, -601L, -1_200L, Long.MIN_VALUE).forEach { time ->
            // SUSPECTED BUG: Kotlin's % keeps the sign, so (timeMs / handOffMs)
            // % 3 is negative for a negative clock; holder becomes -1/-2 and
            // angles[holder] in arcStart throws IndexOutOfBoundsException.
            val frame = relayFrame(time, RelayPhase.Working)

            assertTrue("holder ${frame.holder} at $time", frame.holder in 0..2)
            assertEquals("angles at $time", 3, frame.angles.size)
        }
    }

    @Test
    fun longMaxValueDoesNotOverflowTheFrame() {
        listOf(Long.MAX_VALUE, Long.MAX_VALUE - 1).forEach { time ->
            RelayPhase.values().forEach { phase ->
                val frame = relayFrame(time, phase)

                assertEquals("holders at $time", 3, frame.glow.size)
                assertTrue("holder ${frame.holder} at $time", frame.holder in 0..2)
                assertTrue("glow at $time", frame.glow.all { it in REST_GLOW..1f })
                assertTrue("angles at $time", frame.angles.all { it.isFinite() })
                assertTrue("arc $time", frame.arcSweep >= 0f)
                assertTrue("arc $time", frame.arcSweep <= (2 * PI / 3).toFloat())
                assertTrue("star at $time", frame.starAngle.isFinite())
            }
        }
    }

    @Test
    fun theStillFrameIgnoresAnyClockAndKeepsTheRestPose() {
        val zero = relayFrame(0L, RelayPhase.Tool, still = true)

        assertEquals(zero, relayFrame(Long.MAX_VALUE, RelayPhase.Working, still = true))
        assertEquals(zero, relayFrame(-999_999L, RelayPhase.Thinking, still = true))
        assertEquals(listOf(1f, REST_GLOW, REST_GLOW), zero.glow)
        assertEquals(0, zero.holder)
        assertEquals(0f, zero.arcSweep, 0f)
    }

    @Test
    fun aFullSpinRepeatsTheStartingAngles() {
        RelayPhase.values().forEach { phase ->
            val start = relayFrame(0L, phase)
            val afterOneLap = relayFrame(phase.spinMs, phase)

            start.angles.indices.forEach { index ->
                assertEquals(
                    "angle $index of ${phase.name}",
                    start.angles[index],
                    afterOneLap.angles[index],
                    1e-5f
                )
            }
        }
    }

    @Test
    fun sweepAndGlowStayBoundedAcrossAFullLapForEveryPhase() {
        RelayPhase.values().forEach { phase ->
            val third = (2 * PI / 3).toFloat()
            var sawArc = false
            var time = 0L
            while (time < phase.spinMs) {
                val frame = relayFrame(time, phase)

                assertEquals("glow size at $time", 3, frame.glow.size)
                assertTrue("holder ${frame.holder} at $time/${phase.name}", frame.holder in 0..2)
                assertTrue("glow at $time/${phase.name}", frame.glow.all { it in REST_GLOW..1f })
                assertTrue("sweep at $time/${phase.name}", frame.arcSweep in 0f..third)
                assertTrue("arc start at $time/${phase.name}", frame.arcStart.isFinite())
                assertTrue("star at $time/${phase.name}", frame.starAngle in 0f..(2 * PI).toFloat())
                if (frame.arcSweep > 0f) sawArc = true
                time += 7L
            }
            assertTrue("an arc appears during ${phase.name}", sawArc)
        }
    }
}
