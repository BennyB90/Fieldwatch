package app.fieldwatch.radio

import org.junit.Assert.assertEquals
import org.junit.Test

class WifiChannelTest {
    @Test fun channelFourteenUsesItsExceptionalFrequency() {
        assertEquals(1, WifiRadio.channelOf(2412))
        assertEquals(13, WifiRadio.channelOf(2472))
        assertEquals(14, WifiRadio.channelOf(2484))
        assertEquals(36, WifiRadio.channelOf(5180))
        assertEquals(1, WifiRadio.channelOf(5955))
    }
}
