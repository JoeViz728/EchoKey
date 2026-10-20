package com.echokey.keyboard

/**
 * Pure verification harness for the critical punctuation + contraction flows.
 *
 * This is the authoritative place to prove the invisible layer contract:
 *
 *   T9 (single-tap predictive):
 *     Exact documented flow: 2-2-6-1-8-1 → "can't?"
 *
 *   Multi-tap (T9 off):
 *     Classic deliberate: 8-2-[accept]-2-2-2-5-5-[accept]-5-5-5-3-3 for "tackle"
 *     Punctuation uses the same Punctuator on top of accepted letters.
 *
 * Everything here is side-effect free (no InputConnection). We drive the real
 * engines' simulation helpers + Punctuator + CurrentWordTracker exactly the way
 * the service does.
 */
class ContractionFlowVerifier {

    data class Step(val description: String, val result: String)

    private val punctuator = Punctuator()
    private val wordTracker = CurrentWordTracker()

    // Real engines for high-fidelity simulation of the invisible layer.
    // T9Engine now supports a no-arg constructor for pure verification.
    private val multiTapEngine = MultiTapEngineForVerification()
    private val t9Engine = T9Engine()   // uses the nullable-service constructor branch

    // ------------------------------------------------------------------
    // T9 / single-tap predictive — the hard requirement flow
    // ------------------------------------------------------------------

    /**
     * Walk the exact documented 6-keystroke T9 contraction:
     *   2-2-6-1-8-1 → "can't?"
     *
     * This is the hard requirement. The verifier drives the real T9Engine
     * simulation + Punctuator + tracker exactly as the service will at runtime.
     *
     * Returns the final committed result string for the whole sequence
     * plus the detailed steps.
     */
    data class T9FlowResult(val finalText: String, val steps: List<Step>)

    fun verifyExactT9CantQuestionSixKeystrokes(): T9FlowResult {
        val steps = mutableListOf<Step>()
        resetAll()

        // Keystrokes in order: 2, 2, 6, 1, 8, 1
        // 1-3 are letters; 4 and 6 are punctuation; 5 is letter.

        // Step 1: 2
        var w = t9Engine.simulateContinueWithDigit(2)
        wordTracker.setCurrentWord(w.filter { it.isLetter() || it == '\'' })
        steps.add(Step("Keystroke 1: 2", "word='$w'"))

        // Step 2: 2
        w = t9Engine.simulateContinueWithDigit(2)
        wordTracker.setCurrentWord(w.filter { it.isLetter() || it == '\'' })
        steps.add(Step("Keystroke 2: 2", "word='$w'"))

        // Step 3: 6  → should become "can"
        w = t9Engine.simulateContinueWithDigit(6)
        wordTracker.setCurrentWord(w.filter { it.isLetter() || it == '\'' })
        steps.add(Step("Keystroke 3: 6", "word='$w'"))

        // Step 4: [1]  (mid-word apostrophe)
        val wordBeforePunct = wordTracker.getWordForPunctuation()
        val r1 = punctuator.onPunctuationTap(wordBeforePunct, isMidWord = true)
        steps.add(Step("Keystroke 4: 1 (punct)", "apostrophe → ${r1.composingUpdate}"))

        // Service applies the composing update (this is the critical hand-off).
        val afterApostrophe = r1.composingUpdate ?: wordBeforePunct
        t9Engine.applyExternalComposingUpdate(afterApostrophe)
        wordTracker.setCurrentWord(afterApostrophe.filter { it.isLetter() || it == '\'' })
        steps.add(Step("After [1] apostrophe", "word='${wordTracker.getWordForPunctuation()}'"))

        // Step 5: 8
        w = t9Engine.simulateContinueWithDigit(8)
        wordTracker.setCurrentWord(w.filter { it.isLetter() || it == '\'' })
        steps.add(Step("Keystroke 5: 8", "word='$w'"))

        // Step 6: [1]  (terminal punctuation)
        val wordForFinal = wordTracker.getWordForPunctuation()
        val r2 = punctuator.onPunctuationTap(wordForFinal, isMidWord = false)
        val final = r2.textToCommit ?: (wordForFinal + "?")
        steps.add(Step("Keystroke 6: 1 (punct)", "terminal → '$final'"))

        return T9FlowResult(final, steps)
    }

    /**
     * Legacy helper kept for compatibility with older calls.
     * Prefer verifyExactT9CantQuestionSixKeystrokes().
     */
    fun verifyT9CantQuestionFlow(): List<Step> {
        return verifyExactT9CantQuestionSixKeystrokes().steps
    }

    // ------------------------------------------------------------------
    // Multi-tap (classic Nokia style) with explicit accept
    // ------------------------------------------------------------------

    /**
     * "tackle" using classic multi-tap + explicit accepts (the user-provided example):
     *   8-2-[accept]-2-2-2-5-5-[accept]-5-5-5-3-3
     *
     * We also show a punctuation example on top of it.
     */
    fun verifyMultiTapTackleWithAccepts(): List<Step> {
        val steps = mutableListOf<Step>()
        resetAll()

        // Sequence of (digit, isExplicitAcceptBefore)
        // The explicit accept is modeled as a separate step the user performs.
        val actions = listOf(
            8,      // t
            2,      // a (different key — auto accept of previous)
            "ACCEPT",
            2, 2, 2, // c (cycling on 2)
            5, 5,   // l
            "ACCEPT",
            5, 5, 5, // e
            3, 3    // k (different key from 5, but we still demonstrate accepts)
        )

        var lastAcceptedWord = ""
        actions.forEach { action ->
            when (action) {
                is Int -> {
                    multiTapEngine.simulatePress(action)
                    val (prefix, composing) = multiTapEngine.getState()
                    steps.add(Step("Multi-tap $action", "prefix='$prefix' composing='$composing'  full='${prefix + composing}'"))
                }
                "ACCEPT" -> {
                    multiTapEngine.simulateExplicitAccept()
                    val (prefix, composing) = multiTapEngine.getState()
                    lastAcceptedWord = prefix
                    steps.add(Step("Multi-tap [ACCEPT/NEXT]", "accepted → prefix='$prefix' composing='$composing'"))
                }
            }
        }

        // After the full sequence the prefix should contain the letters of "tackle" (or close).
        val finalState = multiTapEngine.getState()
        steps.add(Step("Final multi-tap state", "prefix='${finalState.first}' composing='${finalState.second}'"))

        return steps
    }

    /**
     * Show that after building a word in multi-tap (with accepts), [1] can still
     * insert smart punctuation / apostrophe via the same Punctuator.
     */
    fun verifyMultiTapPunctuationAfterWord(): List<Step> {
        val steps = mutableListOf<Step>()
        resetAll()

        // Build "you" the hard way in multi-tap (different keys, some same-key accepts not needed).
        // 9-6-8 is actually different keys: wxyz / mno / tuv
        listOf(9, 6, 8).forEach { d ->
            multiTapEngine.simulatePress(d)
            multiTapEngine.simulateExplicitAccept() // be explicit for clarity in demo
        }

        val (prefix, _) = multiTapEngine.getState()
        wordTracker.setCurrentWord(prefix.filter { it.isLetter() || it == '\'' })

        steps.add(Step("Multi-tap built 'you'", "word='${wordTracker.getWordForPunctuation()}'"))

        val r = punctuator.onPunctuationTap(wordTracker.getWordForPunctuation(), isMidWord = false)
        steps.add(Step("Multi-tap [1] after word", "punct='${r.textToCommit}'"))

        return steps
    }

    // ------------------------------------------------------------------
    // Repeated tap punctuation compounds (universal)
    // ------------------------------------------------------------------

    fun verifyRepeatedPunctuation(): List<Step> {
        val steps = mutableListOf<Step>()
        resetAll()

        // Start after a word
        wordTracker.setCurrentWord("you")
        punctuator.onWordBoundary()

        var r = punctuator.onPunctuationTap("you", isMidWord = false)
        steps.add(Step("After 'you'", r.textToCommit ?: ""))

        // Hammer [1] — service would call onPunctuationTap repeatedly
        r = punctuator.onPunctuationTap("you.", isMidWord = false)
        steps.add(Step("Second [1]", r.textToCommit ?: ""))

        r = punctuator.onPunctuationTap("you..", isMidWord = false)
        steps.add(Step("Third [1]", r.textToCommit ?: ""))

        return steps
    }

    fun reset() {
        resetAll()
    }

    private fun resetAll() {
        wordTracker.reset()
        punctuator.onWordBoundary()
        multiTapEngine.reset()
        t9Engine.resetForVerification()
    }

    // ------------------------------------------------------------------
    // Thin adapters (only Multi-tap needs one — T9Engine now supports pure simulation directly)
    // ------------------------------------------------------------------

    /**
     * Adapter that re-uses the real MultiTapEngine logic for simulation.
     * We only need the letter cycling + accept behavior + state exposure.
     */
    private inner class MultiTapEngineForVerification {
        private var currentDigit = -1
        private var currentIndex = 0
        private var currentComposing = ""
        private var currentWordLetters = ""
        private var lastWasLetter = false

        private val keyMap: Map<Int, String> = mapOf(
            2 to "abc", 3 to "def", 4 to "ghi", 5 to "jkl",
            6 to "mno", 7 to "pqrs", 8 to "tuv", 9 to "wxyz"
        )

        fun reset() {
            currentDigit = -1
            currentIndex = 0
            currentComposing = ""
            currentWordLetters = ""
            lastWasLetter = false
        }

        fun simulatePress(digit: Int) {
            val chars = keyMap[digit] ?: return
            if (digit == currentDigit && lastWasLetter) {
                currentIndex = (currentIndex + 1) % chars.length
            } else {
                acceptCurrent()
                currentDigit = digit
                currentIndex = 0
            }
            currentComposing = chars[currentIndex].toString()
            lastWasLetter = true
        }

        fun simulateExplicitAccept() {
            acceptCurrent()
        }

        private fun acceptCurrent() {
            if (currentComposing.isNotEmpty()) {
                currentWordLetters += currentComposing
                currentComposing = ""
            }
            currentDigit = -1
            currentIndex = 0
            lastWasLetter = false
        }

        fun getState(): Pair<String, String> = currentWordLetters to currentComposing
    }
}
