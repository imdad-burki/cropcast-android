package com.imdadburki.cropcast.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingStateTest {

    @Test
    fun `happy path idle to completed`() {
        val m = RecordingStateManager()
        assertTrue(m.transitionTo(RecordingState.REQUESTING_PERMISSION))
        assertTrue(m.transitionTo(RecordingState.INITIALIZING))
        assertTrue(m.transitionTo(RecordingState.RECORDING))
        assertTrue(m.transitionTo(RecordingState.PAUSED))
        assertTrue(m.transitionTo(RecordingState.RECORDING))
        assertTrue(m.transitionTo(RecordingState.STOPPING))
        assertTrue(m.transitionTo(RecordingState.COMPLETED))
        assertTrue(m.transitionTo(RecordingState.IDLE))
        assertEquals(RecordingState.IDLE, m.state)
    }

    @Test
    fun `cannot record without permission request`() {
        val m = RecordingStateManager()
        assertFalse(m.transitionTo(RecordingState.RECORDING))
        assertFalse(m.transitionTo(RecordingState.PAUSED))
        assertFalse(m.transitionTo(RecordingState.COMPLETED))
        assertEquals(RecordingState.IDLE, m.state)
    }

    @Test
    fun `cannot pause when idle or resume when recording`() {
        val m = RecordingStateManager()
        assertFalse(m.canPause())
        assertFalse(m.canResume())
        m.transitionTo(RecordingState.REQUESTING_PERMISSION)
        m.transitionTo(RecordingState.INITIALIZING)
        m.transitionTo(RecordingState.RECORDING)
        assertTrue(m.canPause())
        assertFalse(m.canResume())
        assertTrue(m.canStop())
    }

    @Test
    fun `permission denied returns to idle`() {
        val m = RecordingStateManager()
        m.transitionTo(RecordingState.REQUESTING_PERMISSION)
        assertTrue(m.transitionTo(RecordingState.IDLE))
    }

    @Test
    fun `failure from recording then reset`() {
        val m = RecordingStateManager()
        m.transitionTo(RecordingState.REQUESTING_PERMISSION)
        m.transitionTo(RecordingState.INITIALIZING)
        m.transitionTo(RecordingState.RECORDING)
        assertTrue(m.transitionTo(RecordingState.FAILED))
        assertFalse(m.transitionTo(RecordingState.RECORDING))
        assertTrue(m.transitionTo(RecordingState.IDLE))
    }

    @Test
    fun `double stop is rejected`() {
        val m = RecordingStateManager()
        m.transitionTo(RecordingState.REQUESTING_PERMISSION)
        m.transitionTo(RecordingState.INITIALIZING)
        m.transitionTo(RecordingState.RECORDING)
        assertTrue(m.transitionTo(RecordingState.STOPPING))
        assertFalse(m.transitionTo(RecordingState.STOPPING))
    }
}
