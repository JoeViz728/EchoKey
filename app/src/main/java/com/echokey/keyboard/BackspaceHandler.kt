package com.echokey.keyboard

/**
 * BackspaceHandler
 *
 * Invisible, stateful handler for all the detailed backspace behaviors decided in the design notes:
 *
 * - Single tap:
 *     - If selection exists → delete selection
 *     - Else → delete one character (grapheme aware in future)
 *
 * - Long-press (continuous):
 *     - Delete selection first if present at start of hold
 *     - Then repeat single-char deletion at base rate
 *     - After 1 second of holding → accelerate up to ~4×
 *
 * - Slide left while holding:
 *     - Switch to whole-word deletion
 *     - Starts at ~¼ speed (4× the char interval)
 *     - Same acceleration rule applies
 *
 * - Must protect composing regions (never leave ghost text).
 * - Must only do word deletion on explicit left slide.
 *
 * This class is pure logic + timing state. The service will feed it timing and gesture events
 * and ask it what deletions to perform.
 */
class BackspaceHandler {

    enum class Mode {
        IDLE,
        CHAR_REPEAT,
        WORD_REPEAT
    }

    private var mode: Mode = Mode.IDLE
    private var holdStartTimeMs: Long = 0
    private var lastDeletionTimeMs: Long = 0

    // Base interval in ms for single character deletion.
    private val BASE_CHAR_INTERVAL_MS = 120L

    // Acceleration starts after this many ms of continuous hold.
    private val ACCELERATION_START_MS = 1000L

    // Cap at roughly 4x speed.
    private val MIN_INTERVAL_MS = 30L

    // For word mode we start slower.
    private val WORD_MODE_MULTIPLIER = 4

    private var hasDeletedSelectionOnThisHold = false

    /**
     * Call when the user first presses backspace (down event).
     * Returns whether a selection deletion should happen immediately.
     */
    fun onBackspaceDown(hasSelection: Boolean, nowMs: Long): DeletionAction {
        reset()
        holdStartTimeMs = nowMs
        lastDeletionTimeMs = nowMs
        hasDeletedSelectionOnThisHold = false

        if (hasSelection) {
            hasDeletedSelectionOnThisHold = true
            return DeletionAction.DELETE_SELECTION
        }
        return DeletionAction.DELETE_ONE_CHAR
    }

    /**
     * Call repeatedly while the user holds backspace (from a timer or motion events).
     */
    fun onBackspaceHeld(nowMs: Long, isSlidingLeft: Boolean): DeletionAction {
        if (mode == Mode.IDLE) {
            mode = if (isSlidingLeft) Mode.WORD_REPEAT else Mode.CHAR_REPEAT
        }

        // If user starts sliding left during a char hold, switch modes.
        if (isSlidingLeft && mode == Mode.CHAR_REPEAT) {
            mode = Mode.WORD_REPEAT
            lastDeletionTimeMs = nowMs // reset timing for new cadence
        }

        val interval = getCurrentInterval()

        if (nowMs - lastDeletionTimeMs >= interval) {
            lastDeletionTimeMs = nowMs
            return when (mode) {
                Mode.CHAR_REPEAT -> DeletionAction.DELETE_ONE_CHAR
                Mode.WORD_REPEAT -> DeletionAction.DELETE_ONE_WORD
                else -> DeletionAction.NONE
            }
        }

        return DeletionAction.NONE
    }

    /**
     * Call when the finger lifts from backspace.
     */
    fun onBackspaceUp() {
        reset()
    }

    /**
     * The engine / service should call this after performing a composing-aware delete
     * so the handler can stay in sync if needed.
     */
    fun notifyDeletionPerformed() {
        // Hook for future state if we need to track how many words were deleted, etc.
    }

    private fun getCurrentInterval(): Long {
        // Acceleration is based purely on time since the finger first touched backspace.
        // The service passes the current wall time on every onBackspaceHeld(nowMs) call.
        // We keep lastDeletionTimeMs updated to the most recent 'nowMs' right before
        // deciding whether a deletion should fire, so it serves as "current time".
        val now = lastDeletionTimeMs
        val elapsed = now - holdStartTimeMs

        val base = when (mode) {
            Mode.WORD_REPEAT -> BASE_CHAR_INTERVAL_MS * WORD_MODE_MULTIPLIER
            else -> BASE_CHAR_INTERVAL_MS
        }

        if (elapsed < ACCELERATION_START_MS) {
            return base
        }

        // Linear ramp: after 1s of holding we accelerate toward ~4x speed over the next 1.5s.
        val accelProgress = ((elapsed - ACCELERATION_START_MS) / 1500.0).coerceIn(0.0, 1.0)
        val accelerated = (base * (1.0 - accelProgress * 0.75)).toLong()
        return accelerated.coerceAtLeast(MIN_INTERVAL_MS)
    }

    private fun reset() {
        mode = Mode.IDLE
        holdStartTimeMs = 0
        lastDeletionTimeMs = 0
        hasDeletedSelectionOnThisHold = false
    }

    sealed class DeletionAction {
        object NONE : DeletionAction()
        object DELETE_SELECTION : DeletionAction()
        object DELETE_ONE_CHAR : DeletionAction()
        object DELETE_ONE_WORD : DeletionAction()
    }
}
