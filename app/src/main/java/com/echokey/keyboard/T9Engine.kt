package com.echokey.keyboard

import android.view.inputmethod.InputConnection

/**
 * T9Engine
 *
 * Single-tap predictive (T9) behavior:
 *
 * - One press per letter (the classic "type what you want to say" style).
 * - A small built-in lexicon + naive prediction produces a current best word.
 * - The engine keeps the service + CurrentWordTracker in sync via
 *   service.updateCurrentWordComposing so that the [1] punctuation key
 *   sees the right word for apostrophe / terminal decisions.
 *
 * Critical flow (hard requirement):
 *   2-2-6-1-8-1  →  "can't?"
 *     2 → "c" (or starts "can" prediction)
 *     2 → "ca"
 *     6 → "can"
 *     1 → mid-word apostrophe → "can'"
 *     8 → "can't"
 *     1 → "can't?"
 *
 * After apostrophe insertion the engine receives notifyComposingUpdatedForPunctuation
 * and must continue the word correctly on subsequent letter keys.
 *
 * This is a minimal but honest implementation sufficient to prove the invisible
 * layer punctuation contract. A real dictionary + ranking will replace the toy
 * lexicon later without changing the service/engine boundary.
 */
class T9Engine(
    private val service: EchoKeyService?
) : InputEngine {

    /**
     * Secondary constructor for pure simulation / verification contexts
     * where we do not have (and do not need) a real service.
     */
    constructor() : this(null)

    // Dictionary is the single source for word lookup.
    // We use a static one for now; later this can be injected or swapped.
    private val dictionary: Dictionary = StaticDictionary()

    // Convenience for the naive fallback mapping (consistent with the dictionary).
    private fun firstLetterForDigit(d: Int?): Char? = (dictionary as? StaticDictionary)?.firstLetterForDigit(d)
        ?: when (d) { 2 -> 'a'; 3 -> 'd'; 4 -> 'g'; 5 -> 'j'; 6 -> 'm'; 7 -> 'p'; 8 -> 't'; 9 -> 'w'; else -> null }

    private fun t9Signature(word: String): String {
        // Re-compute using the same mapping the StaticDictionary uses.
        return word.lowercase().filter { it.isLetter() }.mapNotNull { ch ->
            when (ch) {
                in "abc" -> '2'; in "def" -> '3'; in "ghi" -> '4'
                in "jkl" -> '5'; in "mno" -> '6'; in "pqrs" -> '7'
                in "tuv" -> '8'; in "wxyz" -> '9'
                else -> null
            }
        }.joinToString("")
    }

    // ------------------------------------------------------------------
    // Current T9 word state
    // ------------------------------------------------------------------

    // Digits pressed for the *current predictive word*.
    private val currentDigits = mutableListOf<Int>()

    // The letters (including apostrophe) that form the "base" of the current word.
    // After a mid-word apostrophe from [1], this becomes e.g. "can'".
    // New letter presses then append predicted suffix.
    private var currentBase: String = ""

    // The best current predicted full word (what we show as composing and feed to tracker).
    private var currentWord: String = ""

    private var lastWasLetter: Boolean = false

    // ------------------------------------------------------------------
    // InputEngine
    // ------------------------------------------------------------------

    override fun handleLetter(digit: Int, ic: InputConnection, shiftState: ShiftState) {
        if (digit !in 2..9) return

        currentDigits.add(digit)
        recomputeCurrentWord(shiftState)
        lastWasLetter = true

        // Feed the full current predicted word to the service/tracker/punctuator.
        service?.updateCurrentWordComposing("", currentWord)
    }

    override fun handlePunctuation(ic: InputConnection) {
        // The service (via Punctuator) already performed the commit or composing update
        // before calling this. For terminal punctuation it will have called
        // notifyExternalCommit(). For mid-word apostrophe it called
        // notifyComposingUpdatedForPunctuation().
        //
        // We must not commit here — that would destroy the apostrophe flow.
        // Just ensure our internal cycling digits are consistent if needed.
        // (Digits list is already managed by the notify path for apostrophe.)
    }

    override fun handleSpace(ic: InputConnection) {
        commitCurrentWord()
        service?.commitText(" ")
        resetWordState()
    }

    /**
     * Returns true if the backspace only affected our internal prediction state (no real text was deleted from the field).
     * Returns false if we delegated a real character deletion to the field.
     */
    override fun handleBackspace(ic: InputConnection): Boolean {
        if (currentDigits.isNotEmpty() || currentWord.isNotEmpty() || currentBase.isNotEmpty()) {
            // We have active T9 state (digits, prediction, or a base after apostrophe).
            if (currentDigits.isNotEmpty()) {
                currentDigits.removeAt(currentDigits.lastIndex)
            } else if (currentBase.isNotEmpty()) {
                // Deleting from a base created by punctuation (e.g. "can'" → backspace removes the ')
                currentBase = currentBase.dropLast(1)
            } else if (currentWord.isNotEmpty()) {
                // Fallback: shrink the current word prediction manually
                // (rare — usually we have digits or base)
                currentWord = currentWord.dropLast(1)
            }

            recomputeCurrentWord(ShiftState.OFF)
            service?.updateCurrentWordComposing("", currentWord)
            return true // engine consumed the backspace internally; do not double-delete
        }

        // Nothing in our composing state — delegate deletion to the field.
        service?.deleteSurroundingText(1, 0)
        lastWasLetter = false
        return false
    }

    override fun handleDirectDigit(digit: Int, ic: InputConnection) {
        // Long-press digit is a hard boundary.
        finishComposing()
        service?.commitText(digit.toString())
        lastWasLetter = false
        resetWordState()
    }

    override fun finishComposing() {
        commitCurrentWord()
    }

    override fun notifyExternalCommit() {
        resetWordState()
    }

    override fun notifyComposingUpdatedForPunctuation(newComposing: String) {
        // The Punctuator (via service) inserted an apostrophe (or other update) mid-word.
        // Example: currentWord was "can", now newComposing = "can'"
        //
        // We treat everything in newComposing (letters + ') as the new committed base.
        // Future letter presses continue this word (the critical contraction path).
        currentBase = newComposing.filter { it.isLetter() || it == '\'' }
        currentDigits.clear()
        currentWord = currentBase
        lastWasLetter = true

        // Keep the visible composing region and tracker in sync.
        service?.updateCurrentWordComposing("", currentWord)
    }

    override fun isInMultiTapCycle(): Boolean = false  // T9 is never in classic multi-tap cycling state.

    override fun getCurrentCandidates(max: Int): List<String> {
        if (currentDigits.isEmpty() && currentBase.isEmpty()) return emptyList()

        val digitSeq = currentDigits.joinToString("")

        // Ask dictionary first for good matches on the current sequence.
        val dictCands = dictionary.getWordsForT9Sequence(digitSeq)
            .filter { it.isNotBlank() }
            .take(max)

        if (dictCands.isNotEmpty()) {
            // If we have a mid-word base (apostrophe), prefix the results.
            return if (currentBase.isNotEmpty()) {
                dictCands.map { baseForCandidate(it) }
            } else dictCands
        }

        // Fallback: at least surface what we are currently composing as the top candidate.
        val current = currentWord
        return if (current.isNotEmpty()) listOf(current) else emptyList()
    }

    private fun baseForCandidate(suffixWord: String): String {
        val baseLetters = currentBase.filter { it.isLetter() }
        return if (suffixWord.length > baseLetters.length) {
            currentBase + suffixWord.substring(baseLetters.length)
        } else currentBase
    }

    // ------------------------------------------------------------------
    // Public helpers for UI / verification (invisible layer)
    // ------------------------------------------------------------------

    /** Current best word we are composing. */
    fun getCurrentWord(): String = currentWord

    /** The raw digit sequence for the current word (useful for debugging / suggestions). */
    fun getCurrentDigitSequence(): List<Int> = currentDigits.toList()

    /** Reset internal state (testing / new field). */
    fun resetForTesting() {
        resetWordState()
    }

    /**
     * Pure simulation (no InputConnection) of pressing the given digits in sequence.
     * Returns the predicted word after each press.
     * This is how we verify that 2-2-6 produces "can".
     */
    fun simulateWordBuild(digits: List<Int>): List<String> {
        val results = mutableListOf<String>()
        resetForTesting()

        digits.forEach { d ->
            currentDigits.add(d)
            recomputeCurrentWord(ShiftState.OFF)
            results.add(currentWord)
        }
        return results
    }

    /** Convenience: simulate pressing a sequence of letter digits (for tests/verification). */
    fun simulateContinueWithDigit(d: Int): String {
        currentDigits.add(d)
        recomputeCurrentWord(ShiftState.OFF)
        return currentWord
    }

    /**
     * Apply an external composing update (exactly as notifyComposingUpdatedForPunctuation does).
     * Used by punctuation mid-word (apostrophe) and by verification harnesses.
     */
    fun applyExternalComposingUpdate(newComposing: String) {
        notifyComposingUpdatedForPunctuation(newComposing)
    }

    /** Reset simulation state (for verification harnesses). */
    fun resetForVerification() {
        resetForTesting()
    }

    // ------------------------------------------------------------------
    // Internal prediction + state
    // ------------------------------------------------------------------

    private fun recomputeCurrentWord(shiftState: ShiftState) {
        val digitSeq = currentDigits.joinToString("")

        // 1. Ask the dictionary for exact matches on this digit sequence.
        var candidates = dictionary.getWordsForT9Sequence(digitSeq)

        val predictedSuffix = if (candidates.isNotEmpty()) {
            candidates.first()
        } else {
            // 2. Try prefix matches via completions.
            val prefixMatches = dictionary.getCompletions(
                currentDigits.mapNotNull { firstLetterForDigit(it) }.joinToString("")
            ).filter { w ->
                // Keep only those whose T9 signature could start with the digits so far.
                val sig = t9Signature(w)
                sig.startsWith(digitSeq)
            }
            if (prefixMatches.isNotEmpty()) {
                prefixMatches.first()
            } else {
                // 3. Honest naive fallback (still produces usable text for punctuation testing).
                naiveFirstLetterWord(currentDigits)
            }
        }

        // Apply shift only to the very first character of the whole word (simple rule).
        val shiftedSuffix = if (predictedSuffix.isNotEmpty() && currentBase.isEmpty()) {
            when (shiftState) {
                ShiftState.ONCE,
                ShiftState.LOCKED -> predictedSuffix.replaceFirstChar { it.uppercase() }
                else -> predictedSuffix
            }
        } else predictedSuffix

        // If we have a base (from mid-word apostrophe or similar external edit),
        // we are predicting the *tail* after the base.
        //
        // Critical: dictionary words like "can't" already contain the apostrophe.
        // currentBase is already "can'", so we must append only the *new letters*
        // (e.g. "t"), never "'t" — otherwise we produce "can''t".
        currentWord = if (currentBase.isNotEmpty()) {
            val baseLettersOnly = currentBase.filter { it.isLetter() }

            // Preferred: words whose letter-signature is base letters + new digits.
            // Example: base="can'", digits=[8] → signature "2268" → "can't".
            val desiredPrefixSig = t9Signature(baseLettersOnly) + currentDigits.joinToString("")
            val dictMatches = dictionary.getWordsForT9Sequence(desiredPrefixSig)
            val bestFromDict = dictMatches.firstOrNull()

            val extraLetters = when {
                bestFromDict != null -> {
                    val dictLetters = bestFromDict.filter { it.isLetter() }
                    if (dictLetters.length > baseLettersOnly.length &&
                        dictLetters.startsWith(baseLettersOnly, ignoreCase = true)
                    ) {
                        dictLetters.substring(baseLettersOnly.length)
                    } else {
                        currentDigits.mapNotNull { firstLetterForDigit(it) }.joinToString("")
                    }
                }
                else -> {
                    // Naive first-letter fallback for digits pressed after the base.
                    currentDigits.mapNotNull { firstLetterForDigit(it) }.joinToString("")
                }
            }
            currentBase + extraLetters
        } else {
            shiftedSuffix
        }
    }

    private fun naiveFirstLetterWord(digits: List<Int>): String {
        if (digits.isEmpty()) return ""
        return digits.mapNotNull { firstLetterForDigit(it) }.joinToString("")
    }

    private fun commitCurrentWord() {
        if (currentWord.isNotEmpty()) {
            service?.commitText(currentWord)
        }
    }

    private fun resetWordState() {
        currentDigits.clear()
        currentBase = ""
        currentWord = ""
        lastWasLetter = false
    }
}
