package com.imdadburki.cropcast.core.media

/**
 * Recording session state machine (spec §7, §13).
 * Every transition is validated — contradictory states are impossible by
 * construction. Pure Kotlin: unit-tested on the JVM.
 */
enum class RecordingState {
    IDLE,
    REQUESTING_PERMISSION,
    INITIALIZING,
    RECORDING,
    PAUSED,
    STOPPING,
    COMPLETED,
    FAILED,
}

class RecordingStateManager(initial: RecordingState = RecordingState.IDLE) {

    private val allowed: Map<RecordingState, Set<RecordingState>> = mapOf(
        RecordingState.IDLE to setOf(
            RecordingState.REQUESTING_PERMISSION,
        ),
        RecordingState.REQUESTING_PERMISSION to setOf(
            RecordingState.INITIALIZING,
            RecordingState.IDLE, // permission denied / cancelled
            RecordingState.FAILED,
        ),
        RecordingState.INITIALIZING to setOf(
            RecordingState.RECORDING,
            RecordingState.FAILED,
            RecordingState.IDLE, // init cancelled
        ),
        RecordingState.RECORDING to setOf(
            RecordingState.PAUSED,
            RecordingState.STOPPING,
            RecordingState.FAILED,
        ),
        RecordingState.PAUSED to setOf(
            RecordingState.RECORDING, // resume
            RecordingState.STOPPING,
            RecordingState.FAILED,
        ),
        RecordingState.STOPPING to setOf(
            RecordingState.COMPLETED,
            RecordingState.FAILED,
            RecordingState.IDLE, // stopped before any frame: nothing to save
        ),
        RecordingState.COMPLETED to setOf(
            RecordingState.IDLE, // start a new session
        ),
        RecordingState.FAILED to setOf(
            RecordingState.IDLE, // acknowledge + reset
        ),
    )

    var state: RecordingState = initial
        private set

    /** Attempt a transition. Returns true only if the transition is legal.
     * Re-asserting the current state is NOT a legal transition: duplicate
     * operations (e.g. a second Stop) must be rejected (spec §13). */
    fun transitionTo(next: RecordingState): Boolean {
        val ok = allowed[state]?.contains(next) == true
        if (ok) state = next
        return ok
    }

    fun canPause(): Boolean = state == RecordingState.RECORDING
    fun canResume(): Boolean = state == RecordingState.PAUSED
    fun canStop(): Boolean =
        state == RecordingState.RECORDING || state == RecordingState.PAUSED
    fun isActive(): Boolean =
        state == RecordingState.RECORDING || state == RecordingState.PAUSED
}
