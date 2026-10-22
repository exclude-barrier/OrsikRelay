package io.github.excludebarrier.orsikrelay.ui.components

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GroupPositionTest {

    @Test
    fun aSingleItemIsRoundOnBothEnds() {
        assertEquals(GroupPosition.Single, groupPosition(0, 1))
    }

    @Test
    fun aGroupHasFirstMiddleAndLast() {
        assertEquals(
            listOf(GroupPosition.First, GroupPosition.Middle, GroupPosition.Middle, GroupPosition.Last),
            (0 until 4).map { groupPosition(it, 4) }
        )
    }

    @Test
    fun twoItemsAreFirstAndLast() {
        assertEquals(listOf(GroupPosition.First, GroupPosition.Last), (0 until 2).map { groupPosition(it, 2) })
    }

    @Test
    fun anIndexOutsideTheGroupIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { groupPosition(3, 3) }
        assertThrows(IllegalArgumentException::class.java) { groupPosition(0, 0) }
    }

    @Test
    fun onlyTheOuterCornersOfAGroupAreRound() {
        val density = Density(1f)
        val size = Size(100f, 100f)
        fun CornerSize.px() = toPx(size, density)
        val first = GroupPosition.First.shape(outer = 20.dp, inner = 6.dp)
        assertEquals(20f, first.topStart.px())
        assertEquals(6f, first.bottomEnd.px())
        val last = GroupPosition.Last.shape(outer = 20.dp, inner = 6.dp)
        assertEquals(6f, last.topEnd.px())
        assertEquals(20f, last.bottomStart.px())
        val middle = GroupPosition.Middle.shape(outer = 20.dp, inner = 6.dp)
        assertEquals(6f, middle.topStart.px())
        assertEquals(6f, middle.bottomStart.px())
        val single = GroupPosition.Single.shape(outer = 24.dp, inner = 6.dp)
        assertEquals(24f, single.topStart.px())
        assertEquals(24f, single.bottomEnd.px())
    }
}
