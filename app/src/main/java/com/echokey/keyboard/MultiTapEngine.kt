package com.echokey.keyboard

import android.view.inputmethod.InputConnection

/**
 * MultiTapEngine — Pure multi-tap (T9 off) engine.
 *
 * Classic deliberate/manual experience (Nokia-style, early phones, PSP software keyboards, etc.).
 *
 * Letter entry (standard mapping):
 *   2     → a
 *   2-2   → b
 *   2-2-2 → c
 *
 * Same physical key consecutive letters ("abc", "tackle") require explicit accept:
 *   2-[accept]-2-2-[accept]-2-2-2
 *
 * Accept mechanisms (user preference):
 *   - Timeout (pause) → service.onMultiTapTimeout()
 *   - Explicit NEXT/accept command → service.onMultiTapAccept()
 *
 * Example "tackle":
 *   8-2-[accept]-2-2-2-5-5-[accept]-5-5-5-3-3
 *
 * Punctuation on [1] in pure multi-tap:
 *   Repeated presses cycle symbols the classic way.
 *   Smart apostrophe / terminal punctuation (Punctuator) still overlays.
 *
 * The 6-keystroke "can't?" example (2-2-6-1-8-1) is for T9/single-tap predictive mode.
 *
 * This engine only handles cycling + composing. The service owns punctuation, tracking, and commit.
 */
class MultiTapEngine(
    private val service: EchoKeyService
) : InputEngine {

    // Classic phone keypad mapping for pure multi-tap (T9 off).
    // First press gives first letter. Deliberate/manual Nokia-style experience.
    //
    //   2     → a
    //   2-2   → b
    //   2-2-2 → c
    //
    // Same-key consecutive letters ("abc", "tackle") require an explicit accept.
    // Accept is either timeout or deliberate NEXT (user preference).
    //
    // "tackle" example:
    //   8-2-[accept]-2-2-2-5-5-[accept]-5-5-5-3-3
    //
    // The documented 6-keystroke "can't?" (2-2-6-1-8-1) is for T9/single-tap, not this mode.
    private val keyMap: Map<Int, String> = mapOf(
        2 to "abc",
        3 to "def",
        4 to "ghi",
        5 to "jkl",
        6 to "mno",
        7 to "pqrs",
        8 to "tuv",
        9 to "wxyz"
    )

    // Current multi-tap state
    private var currentDigit: Int = -1
    private var currentIndex: Int = 0
    private var currentComposing: String = ""

    /**
     * The letters that have been accepted as final for the current word.
     * In classic multi-tap this grows only when the user accepts (timeout or explicit NEXT).
     */
    private var currentWordLetters: String = ""

    // Whether the last action was a letter.
    private var lastWasLetter: Boolean = false

    override fun handleLetter(digit: Int, ic: InputConnection, shiftState: ShiftState) {
        val chars = keyMap[digit] ?: return

        if (digit == currentDigit && lastWasLetter) {
            // Same key repeated → cycle only the last letter position.
            currentIndex = (currentIndex + 1) % chars.length
        } else {
            // Different key (or first after boundary) → accept previous cycling letter
            // into the word. This is the normal way to build multi-letter words on the
            // same physical key in classic multi-tap (press 2 for 'c', accept, press 2 again for 'a').
            acceptCurrentComposingLetter()
            currentDigit = digit
            currentIndex = 0
        }

        val ch = chars[currentIndex]
        val displayed = if (shiftState == ShiftState.ONCE || shiftState == ShiftState.LOCKED) {
            ch.uppercaseChar().toString()
        } else ch.toString()

        currentComposing = displayed
        lastWasLetter = true

        // Keep service + punctuation system in sync with the full current word.
        service.updateCurrentWordComposing(currentWordLetters, currentComposing)
    }

    override fun handlePunctuation(ic: InputConnection) {
        // Service + Punctuator already applied mid-word apostrophe or terminal punct.
        // Do not finishComposing here — that would destroy the "can'" contraction path.
    }

    override fun handleSpace(ic: InputConnection) {
        // Accept whatever letter is currently cycling, then emit the space.
        // After this the word is finished.
        acceptCurrentComposingLetter()
        service.commitText(" ")
        resetWordState()
    }

    /**
     * Returns true if the backspace only affected our internal cycle state (no real text was deleted from the field).
     * Returns false if we delegated a real character deletion to the field.
     */
    override fun handleBackspace(ic: InputConnection): Boolean {
        if (currentComposing.isNotEmpty()) {
            // Still cycling on the current letter (e.g. user tapped 2-2 for 'b' and now backspaces).
            // Just undo the current cycle letter. The word prefix (currentWordLetters) is untouched.
            currentComposing = ""
            currentDigit = -1
            currentIndex = 0
            lastWasLetter = false
            service.setComposingText("") // clear the visible cycling letter

            // Tell the service + tracker what the word now looks like (prefix only).
            service.updateCurrentWordComposing(currentWordLetters, "")
            return true // engine consumed the backspace; do not double-delete from tracker/field
        }

        // Nothing left in our local cycle. Delegate the actual character deletion.
        // The service (performDeletionAction) will decide whether to also step the tracker.
        service.deleteSurroundingText(1, 0)

        // Clean our own state.
        lastWasLetter = false
        currentDigit = -1

        // If the user backspaced a previously accepted letter, we should also shrink our prefix
        // so that punctuation decisions stay correct. Best-effort: drop last char from our model.
        if (currentWordLetters.isNotEmpty()) {
            currentWordLetters = currentWordLetters.dropLast(1)
            service.updateCurrentWordComposing(currentWordLetters, "")
        }
        return false
    }

    override fun handleDirectDigit(digit: Int, ic: InputConnection) {
        // Long-press digit bypasses letter cycling entirely.
        acceptCurrentComposingLetter()
        service.commitText(digit.toString())
        lastWasLetter = false
        currentDigit = -1
    }

    override fun finishComposing() {
        acceptCurrentComposingLetter()
    }

    /**
     * Explicit accept (user pressed a dedicated NEXT / accept key, or the action
     * key temporarily took over the accept role while cycling in multi-tap).
     *
     * This finalizes the current cycling letter without waiting for timeout.
     * The word stays open for more letters or punctuation.
     */
    fun acceptCurrentLetterExplicit() {
        acceptCurrentComposingLetter()
        service.updateCurrentWordComposing(currentWordLetters, currentComposing)
    }

    /**
     * Called by the service when the multi-tap timeout fires
     * (user paused long enough on the current key).
     */
    fun commitCurrentLetterOnTimeout() {
        acceptCurrentComposingLetter()
        service.updateCurrentWordComposing(currentWordLetters, currentComposing)
    }

    // Expose for potential future UI layer (e.g. to show the current cycling options).
    fun getCurrentCycleOptions(): String? = keyMap[currentDigit]
    fun getCurrentCycleIndex(): Int = currentIndex

    /** For Snapshot / UI layer. */
    fun getCurrentWordLettersForSnapshot(): String = currentWordLetters
    fun getCurrentComposingForSnapshot(): String = currentComposing

    /** True while the user is still rapidly pressing the same key to choose a letter. */
    override fun isInMultiTapCycle(): Boolean {
        return currentDigit != -1 && currentComposing.isNotEmpty() && lastWasLetter
    }

    /**
     * For the suggestions bar in pure multi-tap mode.
     * We surface the word built so far (accepted letters + the letter currently being cycled).
     * This is the "current best" the user has explicitly typed.
     */
    override fun getCurrentCandidates(max: Int): List<String> {
        val built = currentWordLetters + currentComposing
        if (built.isEmpty()) return emptyList()
        return listOf(built).take(max)
    }

    // ------------------------------------------------------------------
    // Test / verification helpers (invisible layer only)
    // ------------------------------------------------------------------

    /** Reset all state as if a new field was focused. */
    fun resetForTesting() {
        resetWordState()
    }

    /**
     * Pure state-machine simulation (no InputConnection).
     * Returns list of (currentWordLetters, currentComposing) after each digit.
     * This lets us see exactly what the punctuation system would see.
     */
    fun simulateWordBuild(digits: List<Int>): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        resetForTesting()

        digits.forEach { d ->
            val chars = keyMap[d] ?: return@forEach
            if (d == currentDigit && lastWasLetter) {
                currentIndex = (currentIndex + 1) % chars.length
            } else {
                acceptCurrentComposingLetter()
                currentDigit = d
                currentIndex = 0
            }
            currentComposing = chars[currentIndex].toString()
            lastWasLetter = true
            results.add(currentWordLetters to currentComposing)
        }
        return results
    }

    /**
     * Convenience simulation for multi-tap letter sequences.
     * Note: the documented "2-2-6-1-8-1" flow is a *T9* example.
     * In multi-tap you would do something like 2-[accept]-2-2-6 + [1] apostrophe etc.
     */
    fun simulateWordSequence(digits: List<Int>): List<Pair<String, String>> {
        return simulateWordBuild(digits)
    }

    override fun notifyExternalCommit() {
        // Someone else (service / punctuation / space) committed text.
        // Drop any in-progress cycle and reset word accumulation.
        resetWordState()
    }

    override fun notifyComposingUpdatedForPunctuation(newComposing: String) {
        // The service (via Punctuator) inserted an apostrophe mid-word.
        // Example: engine had "can" (currentWordLetters="ca", composing="n") → punctuation makes "can'"
        //
        // We accept the previous cycling letter into the word state,
        // then treat the entire new string (including the apostrophe) as the new base.
        acceptCurrentComposingLetter()

        // newComposing already contains the full word up to and including the apostrophe (e.g. "can'")
        // We will use this as the new "committed prefix" equivalent.
        currentWordLetters = newComposing.filter { it.isLetter() || it == '\'' }

        currentComposing = ""
        currentDigit = -1
        currentIndex = 0
        lastWasLetter = true
    }

    // ------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /**
     * Accept whatever letter is currently cycling as the final character for its position
     * and fold it into currentWordLetters.
     */
    private fun acceptCurrentComposingLetter() {
        if (currentComposing.isNotEmpty()) {
            currentWordLetters += currentComposing
            currentComposing = ""
        }
        currentDigit = -1
        currentIndex = 0
        lastWasLetter = false
    }

    private fun resetWordState() {
        currentWordLetters = ""
        currentComposing = ""
        currentDigit = -1
        currentIndex = 0
        lastWasLetter = false
    }
}
