package app.fieldwatch.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PayloadLocationTest {
    @Test
    fun unavailableOperatorUpdateCannotRelabelARetainedPosition() {
        val takeoff = PayloadLocation(opLat = 39.9, opLon = -73.9, opKind = OperatorLoc.TAKEOFF)
        val unavailable = listOf(
            PayloadLocation(opLat = 0.0, opLon = 0.0, opKind = OperatorLoc.LIVE),
            PayloadLocation(opLat = 91.0, opLon = -74.0, opKind = OperatorLoc.FIXED),
            PayloadLocation(opLat = null, opLon = -74.0, opKind = OperatorLoc.LIVE),
        )
        unavailable.forEach { update ->
            val kept = update.mergeSticky(takeoff)
            assertEquals(39.9, kept.opLat!!, 0.0)
            assertEquals(-73.9, kept.opLon!!, 0.0)
            assertEquals(OperatorLoc.TAKEOFF, kept.opKind)
        }
    }

    @Test
    fun validOperatorUpdateReplacesPositionAndItsTypeTogether() {
        val takeoff = PayloadLocation(opLat = 39.9, opLon = -73.9, opKind = OperatorLoc.TAKEOFF)
        val live = PayloadLocation(opLat = 40.0, opLon = -74.0, opKind = OperatorLoc.LIVE).mergeSticky(takeoff)
        assertEquals(40.0, live.opLat!!, 0.0)
        assertEquals(-74.0, live.opLon!!, 0.0)
        assertEquals(OperatorLoc.LIVE, live.opKind)
        // A valid position without type evidence must not inherit the old type.
        assertNull(PayloadLocation(opLat = 40.0, opLon = -74.0).mergeSticky(takeoff).opKind)
    }

    @Test
    fun typeMetadataWithoutAnyPositionDoesNotManufactureAPin() {
        val unknown = PayloadLocation(opLat = 0.0, opLon = 0.0, opKind = OperatorLoc.LIVE)
            .mergeSticky(PayloadLocation())
        assertEquals(OperatorLoc.LIVE, unknown.opKind)
        assertNull(unknown.opLat)
        assertNull(unknown.opLon)
    }
}
