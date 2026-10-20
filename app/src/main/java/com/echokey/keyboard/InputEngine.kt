package com.echokey.keyboard

// NOTE: InputConnection comes from either the real Android SDK (full build)
// or from PureAndroidStubs.kt (pure kotlinc verification).
import android.view.inputmethod.InputConnection

/**
 * Minimal interface for pluggable input engines (multi-tap vs T9).
 * This keeps the service clean and the engines testable.
 *
 * The interface itself only needs InputConnection (for direct operations)
 * and ShiftState. Both are satisfied in the pure kotlinc verification path
 * via tiny stubs.
 */
interface InputEngine {
    fun handleLetter(digit: Int, ic: InputConnection, shiftState: ShiftState)
    fun handlePunctuation(ic: InputConnection)
    fun handleSpace(ic: InputConnection)
    fun handleBackspace(ic: InputConnection): Boolean
    fun handleDirectDigit(digit: Int, ic: InputConnection)
    fun finishComposing()

    /**
     * Called when the service performed a punctuation commit that the engine did not initiate.
     * Engines should reset any in-progress letter cycling / composing state.
     */
    fun notifyExternalCommit()

    /**
     * Called when composing text was updated externally (e.g. mid-word apostrophe insertion).
     * The engine may need to adjust its internal current word / cycle state.
     */
    fun notifyComposingUpdatedForPunctuation(newComposing: String)

    /**
     * For multi-tap only: returns true if we are currently in the middle of cycling
     * letters on a key (e.g. user has pressed 2 but not yet accepted 'c').
     * This allows the action key (or other UI) to temporarily act as an explicit NEXT/accept.
     */
    fun isInMultiTapCycle(): Boolean = false

    /**
     * Returns up to [max] word candidates for the current prefix/digit sequence.
     * T9 returns real predictions; multi-tap can return the currently built word or empty.
     * Used by the suggestions bar (when not in private mode).
     */
    fun getCurrentCandidates(max: Int): List<String> = emptyList()
}

/**
 * Safe no-op engine used only briefly before onStartInput replaces it.
 * Does nothing harmful if ever called.
 */
class NoOpEngine : InputEngine {
    override fun handleLetter(digit: Int, ic: InputConnection, shiftState: ShiftState) {}
    override fun handlePunctuation(ic: InputConnection) {}
    override fun handleSpace(ic: InputConnection) {}
    override fun handleBackspace(ic: InputConnection): Boolean = false
    override fun handleDirectDigit(digit: Int, ic: InputConnection) {}
    override fun finishComposing() {}
    override fun notifyExternalCommit() {}
    override fun notifyComposingUpdatedForPunctuation(newComposing: String) {}
    override fun isInMultiTapCycle(): Boolean = false
    override fun getCurrentCandidates(max: Int): List<String> = emptyList()
}
